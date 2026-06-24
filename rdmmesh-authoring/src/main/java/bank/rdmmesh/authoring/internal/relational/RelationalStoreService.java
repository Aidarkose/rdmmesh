package bank.rdmmesh.authoring.internal.relational;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.authoring.internal.CanonicalSnapshot;
import bank.rdmmesh.authoring.internal.dao.PhysicalTableRegistryDao;
import bank.rdmmesh.authoring.internal.dao.RelationalSyncStatusDao;
import bank.rdmmesh.authoring.internal.relational.RelationalDdlBuilder.Column;
import bank.rdmmesh.authoring.resource.CodeItemDto;

/**
 * Relational store (спайк полной замены JSONB), модель версионности — вариант C:
 * на справочник две физические таблицы в схеме {@code rd_data}:
 * <ul>
 *   <li>{@code "<base>__draft"}   — рабочая область авторинга, PK {@code (version_id, <ключи>)};</li>
 *   <li>{@code "<base>__current"} — текущий PUBLISHED-снапшот, PK {@code (<ключи>)} — цель FK.</li>
 * </ul>
 *
 * <p>Write-path пишет в {@code __draft} по {@code version_id}; на publish {@code __current}
 * атомарно пересобирается из draft нужной версии. Колонки выводятся из {@code key_spec}
 * (ключи) и активной CodeSetSchema (атрибуты). Идентификаторы валидируются snake_case,
 * значения биндятся параметрами с {@code CAST(:p AS <type>)} — конкатенации значений нет.
 */
public final class RelationalStoreService {

    private static final Logger log = LoggerFactory.getLogger(RelationalStoreService.class);
    private static final String SCHEMA = "rd_data";

    private final Jdbi jdbi;
    private final CatalogReadPort catalog;
    private final ObjectMapper json;

    public RelationalStoreService(Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json) {
        this.jdbi = jdbi;
        this.catalog = catalog;
        this.json = json;
    }

    // ── publish-хук (Stage 2-final) ───────────────────────────────────────────────

    /**
     * Подписка на {@link VersionPublishedDomainEvent}: после реального publish'а версии
     * (E6, {@code PublishingService}) пересобираем {@code __current} из {@code code_item}
     * этой версии. Регистрируется в {@code AuthoringModule.build} на том же in-process bus,
     * что и publishing.
     */
    public void registerOn(EventBus bus) {
        bus.subscribe(VersionPublishedDomainEvent.class, this::onVersionPublished);
    }

    /**
     * Пересборка {@code __current} после publish'а — post-commit, в собственной tx,
     * <b>best-effort</b> (SPEC §3.8: side-effect не должен ломать business-операцию;
     * вдобавок {@code SyncEventBus} изолирует исключения подписчиков). {@code code_item}
     * остаётся источником истины: {@link #syncFromVersion} бэкфиллит {@code __draft} из
     * него, {@link #publish} атомарно пересобирает из draft текущий снапшот.
     */
    void onVersionPublished(VersionPublishedDomainEvent event) {
        if (event.payload() == null || event.payload().getVersionId() == null) {
            return;
        }
        UUID versionId;
        try {
            versionId = UUID.fromString(event.payload().getVersionId());
        } catch (IllegalArgumentException e) {
            log.warn("relational store: bad version_id в VersionPublishedDomainEvent: {}",
                    event.payload().getVersionId());
            return;
        }
        try {
            // Stage 7c: draft уже источник истины — sync из code_item больше не нужен.
            PublishResult res = publish(versionId);
            upsertSyncStatus(versionId, "OK", null);
            log.info("relational store: __current пересобран после publish version_id={} ({} строк)",
                    versionId, res.rowsPublished());
        } catch (RuntimeException e) {
            // Stage 7 (A): провал пересборки больше не молчит — фиксируем STALE с причиной.
            upsertSyncStatus(versionId, "STALE", shorten(rootMessage(e)));
            log.warn("relational store: пересборка __current после publish version_id={} не удалась: {}",
                    versionId, e.toString());
        }
    }

    // ── publish-gate + sync-status (Stage 7, B+A) ─────────────────────────────────

    /** Sentinel для принудительного rollback'а dry-run'а (это лишь проверка, не запись). */
    private static final class DryRunRollback extends RuntimeException {
        DryRunRollback() {
            super(null, null, false, false);
        }
    }

    /**
     * Сухой прогон пересборки {@code __current} для версии: выполняет
     * {@code DELETE __current} + {@code INSERT из __draft} в транзакции и
     * ПРИНУДИТЕЛЬНО откатывает. Если бизнес-данные нарушают материализованный FK
     * (или иной констрейнт) — возвращает человекочитаемую причину; иначе empty.
     * Сам по себе ничего не меняет.
     */
    public Optional<String> dryRunPublishReason(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        // Тот же upsert-путь, что и боевой publish (вариант 1) — иначе пред-проверка
        // ложно ругалась бы на удаление всех строк __current.
        List<String> merge = currentMergeStatements(t);
        try {
            jdbi.inTransaction(h -> {
                for (String sql : merge) {
                    h.createUpdate(sql).bind("v", versionId).execute();
                }
                throw new DryRunRollback();
            });
            return Optional.empty(); // недостижимо: DryRunRollback всегда бросается
        } catch (DryRunRollback ok) {
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of(shorten(rootMessage(e)));
        }
    }

    /**
     * Пред-проверка перед публикацией (B): если пересборка невозможна — фиксирует
     * статус {@code BLOCKED} с причиной и возвращает её. Empty — публиковать можно.
     */
    public Optional<String> recordPublishBlockReason(UUID versionId) {
        Optional<String> reason = dryRunPublishReason(versionId);
        reason.ifPresent(r -> upsertSyncStatus(versionId, "BLOCKED", r));
        return reason;
    }

    /** Текущий статус синхронизации rd_data для версии (A). */
    public Optional<RelationalSyncStatusDao.SyncStatusRow> syncStatus(UUID versionId) {
        return jdbi.withExtension(RelationalSyncStatusDao.class, d -> d.find(versionId));
    }

    private void upsertSyncStatus(UUID versionId, String state, String reason) {
        try {
            UUID codesetId = codesetIdOf(versionId);
            jdbi.useExtension(RelationalSyncStatusDao.class,
                    d -> d.upsert(versionId, codesetId, state, reason));
        } catch (RuntimeException e) {
            log.warn("relational store: не удалось записать sync-status {} для version_id={}: {}",
                    state, versionId, e.toString());
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? e.toString() : m;
    }

    private static String shorten(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 500 ? one.substring(0, 500) + "…" : one;
    }

    // ── ссылочная целостность (Stage 7): проверка column_refs против __current родителя ──

    /**
     * Жёсткая проверка одной строки: для каждой связи ({@code column_refs}) значение
     * в {@code from_column} должно существовать в опубликованном родителе
     * ({@code <parent>__current.<to_column>}). Возвращает список человекочитаемых
     * нарушений (пусто — всё ок). Используется на add/update/bulk.
     */
    public List<String> referenceViolations(
            UUID codesetId, List<String> keyParts, Map<String, Object> attributes) {
        List<CatalogReadPort.CodeSetReferenceSnapshot> refs = catalog.referencesOf(codesetId);
        if (refs.isEmpty()) {
            return List.of();
        }
        ResolvedTable t = requireProvisioned(codesetId);
        Map<String, Object> cells = new LinkedHashMap<>();
        if (keyParts != null) {
            for (int i = 0; i < t.keyNames.size() && i < keyParts.size(); i++) {
                cells.put(t.keyNames.get(i), keyParts.get(i));
            }
        }
        if (attributes != null) {
            cells.putAll(attributes);
        }
        List<String> out = new ArrayList<>();
        for (CatalogReadPort.CodeSetReferenceSnapshot ref : refs) {
            if (!t.columnTypes.containsKey(ref.fromColumn())) {
                continue; // from_column не материализована — нечего проверять
            }
            Object v = cells.get(ref.fromColumn());
            if (v == null || (v instanceof String s && s.isBlank())) {
                continue; // null/пусто — связь не задана, ок
            }
            ResolvedTable to;
            try {
                to = requireProvisioned(ref.toCodesetId());
            } catch (RuntimeException e) {
                out.add(refMsg(ref, v) + " (родитель не опубликован)");
                continue;
            }
            String type = to.columnTypes.getOrDefault(ref.toColumn(), "text");
            boolean exists = jdbi.withHandle(h -> h.createQuery(
                    "SELECT 1 FROM " + qTable(to.currentTable)
                            + " WHERE " + RelationalDdlBuilder.q(ref.toColumn())
                            + " = CAST(:v AS " + type + ") LIMIT 1")
                    .bind("v", coerce(v, type))
                    .mapTo(Integer.class).findOne().isPresent());
            if (!exists) {
                out.add(refMsg(ref, v));
            }
        }
        return out;
    }

    /**
     * Жёсткая проверка всех строк черновика версии (set-based, по одной выборке на связь).
     * Возвращает нарушения вида {@code from_column=<val> не найден в <parent>.<to_column>}.
     * Используется на submit (DRAFT → IN_REVIEW).
     */
    public List<String> versionReferenceViolations(UUID versionId) {
        UUID codesetId = codesetIdOf(versionId);
        List<CatalogReadPort.CodeSetReferenceSnapshot> refs = catalog.referencesOf(codesetId);
        if (refs.isEmpty()) {
            return List.of();
        }
        ResolvedTable t = requireProvisioned(codesetId);
        List<String> out = new ArrayList<>();
        for (CatalogReadPort.CodeSetReferenceSnapshot ref : refs) {
            if (!t.columnTypes.containsKey(ref.fromColumn())) {
                continue;
            }
            ResolvedTable to;
            try {
                to = requireProvisioned(ref.toCodesetId());
            } catch (RuntimeException e) {
                out.add(refMsg(ref, "*") + " (родитель не опубликован)");
                continue;
            }
            String fromQ = RelationalDdlBuilder.q(ref.fromColumn());
            String toQ = RelationalDdlBuilder.q(ref.toColumn());
            String sql = "SELECT DISTINCT d." + fromQ + "::text AS v"
                    + " FROM " + qTable(t.draftTable) + " d"
                    + " WHERE d.version_id = CAST(:v AS uuid) AND d." + fromQ + " IS NOT NULL"
                    + " AND NOT EXISTS (SELECT 1 FROM " + qTable(to.currentTable) + " p"
                    + " WHERE p." + toQ + " = d." + fromQ + ")";
            List<String> missing = jdbi.withHandle(h ->
                    h.createQuery(sql).bind("v", versionId).mapTo(String.class).list());
            for (String mv : missing) {
                out.add(refMsg(ref, mv));
            }
        }
        return out;
    }

    /**
     * Обратная проверка (сторона РОДИТЕЛЯ) на submit: если черновик версии убирает ключ
     * {@code __current}, на который ещё ссылается ребёнок (по материализованному FK
     * ребёнок→родитель), — это нарушение, потому что публикация такого черновика упала бы
     * на удалении строки родителя. Возвращает нарушения вида
     * {@code <parent>.<key>=<val> ещё используется в <child>.<col>}. Пусто — всё ок.
     *
     * <p>Источник «детей» — реальные {@code FOREIGN KEY} в {@code rd_data} ({@code pg_constraint}),
     * целью которых служит {@code __current} этого справочника; это в точности связи, которые
     * физически блокируют publish.
     */
    public List<String> versionRemovedReferencedViolations(UUID versionId) {
        UUID codesetId = codesetIdOf(versionId);
        ResolvedTable t = requireProvisioned(codesetId);
        // Входящие FK: child_table.child_col → this.__current.parent_col (по одной колонке).
        String inboundSql = "SELECT child.relname AS child_table,"
                + " ac.attname AS child_col, ap.attname AS parent_col"
                + " FROM pg_constraint con"
                + " JOIN pg_class child ON child.oid = con.conrelid"
                + " JOIN pg_class parent ON parent.oid = con.confrelid"
                + " JOIN unnest(con.conkey)  WITH ORDINALITY AS ck(attnum, ord) ON true"
                + " JOIN unnest(con.confkey) WITH ORDINALITY AS cf(attnum, ord) ON cf.ord = ck.ord"
                + " JOIN pg_attribute ac ON ac.attrelid = con.conrelid  AND ac.attnum = ck.attnum"
                + " JOIN pg_attribute ap ON ap.attrelid = con.confrelid AND ap.attnum = cf.attnum"
                + " WHERE con.contype = 'f'"
                + " AND parent.relnamespace = '" + SCHEMA + "'::regnamespace"
                + " AND parent.relname = :parent";
        List<Map<String, Object>> inbound = jdbi.withHandle(h ->
                h.createQuery(inboundSql).bind("parent", t.currentTable).mapToMap().list());
        if (inbound.isEmpty()) {
            return List.of();
        }
        String parentName = catalog.findCodeSet(codesetId)
                .map(CatalogReadPort.CodeSetSnapshot::name).orElse(t.currentTable);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> fk : inbound) {
            String childTable = (String) fk.get("child_table");
            String childCol = (String) fk.get("child_col");
            String parentCol = (String) fk.get("parent_col");
            String pcQ = RelationalDdlBuilder.q(parentCol);
            String ccQ = RelationalDdlBuilder.q(childCol);
            // Ключи родителя, которых нет в новом черновике (будут удалены), но на них ещё ссылается ребёнок.
            String sql = "SELECT DISTINCT cur." + pcQ + "::text AS v"
                    + " FROM " + qTable(t.currentTable) + " cur"
                    + " WHERE NOT EXISTS (SELECT 1 FROM " + qTable(t.draftTable) + " d"
                    + " WHERE d.version_id = CAST(:v AS uuid) AND d." + pcQ + " = cur." + pcQ + ")"
                    + " AND EXISTS (SELECT 1 FROM " + qTable(childTable) + " ch"
                    + " WHERE ch." + ccQ + " = cur." + pcQ + ")";
            List<String> stillUsed = jdbi.withHandle(h ->
                    h.createQuery(sql).bind("v", versionId).mapTo(String.class).list());
            String childName = childTable.replaceFirst("__current$", "");
            for (String val : stillUsed) {
                out.add(parentName + "." + parentCol + "=" + val
                        + " ещё используется в " + childName + "." + childCol
                        + " — сначала уберите ссылку у ребёнка");
            }
        }
        return out;
    }

    private String refMsg(CatalogReadPort.CodeSetReferenceSnapshot ref, Object value) {
        String parent = catalog.findCodeSet(ref.toCodesetId())
                .map(CatalogReadPort.CodeSetSnapshot::name)
                .orElse(ref.toCodesetId().toString());
        return ref.fromColumn() + "=" + value + " не найден в " + parent + "." + ref.toColumn();
    }

    // ── provision ───────────────────────────────────────────────────────────────

    /** Создаёт (IF NOT EXISTS) обе таблицы справочника (draft + current) и регистрирует базу. */
    public ProvisionResult provision(UUID codesetId) {
        ResolvedTable t = resolve(codesetId);

        String currentDdl = RelationalDdlBuilder.createTableWithPk(
                SCHEMA, t.currentTable, t.dataColumns, t.keyNames);

        // draft и history имеют одинаковую форму: version_id + data, PK (version_id, ключи).
        List<Column> versionedColumns = new ArrayList<>();
        versionedColumns.add(RelationalDdlBuilder.VERSION_ID);
        versionedColumns.addAll(t.dataColumns);
        List<String> versionedPk = new ArrayList<>();
        versionedPk.add(RelationalDdlBuilder.VERSION_ID.name());
        versionedPk.addAll(t.keyNames);
        String draftDdl = RelationalDdlBuilder.createTableWithPk(
                SCHEMA, t.draftTable, versionedColumns, versionedPk);
        String historyDdl = RelationalDdlBuilder.createTableWithPk(
                SCHEMA, t.historyTable, versionedColumns, versionedPk);

        jdbi.useHandle(h -> {
            h.execute(currentDdl);
            h.execute(draftDdl);
            h.execute(historyDdl);
            // Stage 4-lite: идемпотентно доращиваем новые колонки на уже существующих
            // таблицах (description/parent_key/order_index, эволюция атрибутов).
            h.execute(RelationalDdlBuilder.addColumnsIfNotExists(SCHEMA, t.currentTable, t.dataColumns));
            h.execute(RelationalDdlBuilder.addColumnsIfNotExists(SCHEMA, t.draftTable, t.dataColumns));
            h.execute(RelationalDdlBuilder.addColumnsIfNotExists(SCHEMA, t.historyTable, t.dataColumns));
            h.attach(PhysicalTableRegistryDao.class)
                    .upsert(codesetId, SCHEMA, t.base, t.schemaVersion);
        });
        log.info("relational store: материализован codeset_id={} → {}.{}__{{draft,current}}",
                codesetId, SCHEMA, t.base);
        return new ProvisionResult(
                SCHEMA, t.draftTable, t.currentTable, t.historyTable,
                new ArrayList<>(t.columnTypes.keySet()));
    }

    // ── write-path (draft) ───────────────────────────────────────────────────────

    /** Upsert одной строки черновика {@code version_id} — по ячейке на переданное значение. */
    public void upsertDraftRow(UUID versionId, Map<String, Object> cells) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        validateCells(t, cells);
        jdbi.useHandle(h -> upsertDraft(h, t, versionId, cells));
    }

    /** Удаляет строку черновика по ключу (в {@code keyCells} должны быть все ключевые колонки). */
    public void deleteDraftRow(UUID versionId, Map<String, Object> keyCells) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        for (String key : t.keyNames) {
            if (keyCells == null || keyCells.get(key) == null) {
                throw new IllegalArgumentException("key column is required: " + key);
            }
        }
        StringBuilder where = new StringBuilder("version_id = CAST(:pv AS uuid)");
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("pv", versionId);
        int i = 0;
        for (String key : t.keyNames) {
            String p = "k" + i++;
            where.append(" AND ").append(RelationalDdlBuilder.q(key))
                    .append(" = CAST(:").append(p).append(" AS ").append(t.columnTypes.get(key)).append(')');
            binds.put(p, coerce(keyCells.get(key), t.columnTypes.get(key)));
        }
        String sql = "DELETE FROM " + qTable(t.draftTable) + " WHERE " + where;
        jdbi.useHandle(h -> {
            var u = h.createUpdate(sql);
            binds.forEach(u::bind);
            u.execute();
        });
    }


    // ── publish ───────────────────────────────────────────────────────────────

    /** Пересобирает {@code __current} из draft указанной версии (атомарно) и фиксирует версию. */
    public PublishResult publish(UUID versionId) {
        UUID codesetId = codesetIdOf(versionId);
        ResolvedTable t = requireProvisioned(codesetId);

        StringBuilder cols = new StringBuilder();
        for (Column c : t.dataColumns) {
            if (cols.length() > 0) cols.append(", ");
            cols.append(RelationalDdlBuilder.q(c.name()));
        }
        // История: снимок этой версии в __history (version_id + data), без перезатирания других версий.
        String vidCol = RelationalDdlBuilder.q("version_id");
        String historyInsert = "INSERT INTO " + qTable(t.historyTable) + " (" + vidCol + ", " + cols + ")"
                + " SELECT " + vidCol + ", " + cols + " FROM " + qTable(t.draftTable)
                + " WHERE version_id = CAST(:v AS uuid)";
        // Вариант 1 (upsert): обновляем совпавшие ключи, добавляем новые, удаляем только реально
        // исчезнувшие строки. Пережившие версию строки __current не трогаются — поэтому
        // материализованный FK ребёнок→родитель больше не падает на пересборке родителя
        // (раньше DELETE всех строк __current ломал publish, пока на них ссылались дети).
        List<String> merge = currentMergeStatements(t);

        int upserted = jdbi.inTransaction(h -> {
            int n = h.createUpdate(merge.get(0)).bind("v", versionId).execute(); // upsert строк версии
            for (int i = 1; i < merge.size(); i++) {
                h.createUpdate(merge.get(i)).bind("v", versionId).execute();      // удалить исчезнувшие
            }
            // Идемпотентно по версии: перезаписываем снимок именно этой версии в истории.
            h.createUpdate("DELETE FROM " + qTable(t.historyTable) + " WHERE version_id = CAST(:v AS uuid)")
                    .bind("v", versionId).execute();
            h.createUpdate(historyInsert).bind("v", versionId).execute();
            h.attach(PhysicalTableRegistryDao.class).setPublishedVersion(codesetId, versionId);
            return n;
        });
        log.info("relational store: publish version_id={} → {}: {} строк", versionId, t.currentTable, upserted);
        return new PublishResult(codesetId, t.currentTable, upserted);
    }

    /**
     * SQL пересборки {@code __current} из draft версии способом upsert (вариант 1):
     * (1) {@code INSERT … ON CONFLICT (ключи) DO UPDATE SET …=EXCLUDED} — обновляет совпавшие
     * ключи и добавляет новые, не удаляя пережившие версию строки; (2) {@code DELETE} строк,
     * ключей которых нет в новой версии. Оба statement'а биндят {@code :v = version_id}; порядок
     * важен — сначала upsert, потом удаление исчезнувших. Если у справочника нет неключевых
     * колонок — {@code DO NOTHING} (нечего обновлять).
     */
    private List<String> currentMergeStatements(ResolvedTable t) {
        StringBuilder cols = new StringBuilder();
        StringBuilder setClause = new StringBuilder();
        for (Column c : t.dataColumns) {
            if (cols.length() > 0) cols.append(", ");
            cols.append(RelationalDdlBuilder.q(c.name()));
            if (!t.keyNames.contains(c.name())) {
                if (setClause.length() > 0) setClause.append(", ");
                setClause.append(RelationalDdlBuilder.q(c.name()))
                        .append(" = EXCLUDED.").append(RelationalDdlBuilder.q(c.name()));
            }
        }
        StringBuilder keyCols = new StringBuilder();
        StringBuilder keyMatch = new StringBuilder();
        for (String k : t.keyNames) {
            if (keyCols.length() > 0) keyCols.append(", ");
            keyCols.append(RelationalDdlBuilder.q(k));
            if (keyMatch.length() > 0) keyMatch.append(" AND ");
            keyMatch.append("d.").append(RelationalDdlBuilder.q(k))
                    .append(" = c.").append(RelationalDdlBuilder.q(k));
        }
        String onConflict = setClause.length() == 0
                ? " ON CONFLICT (" + keyCols + ") DO NOTHING"
                : " ON CONFLICT (" + keyCols + ") DO UPDATE SET " + setClause;
        String upsert = "INSERT INTO " + qTable(t.currentTable) + " (" + cols + ")"
                + " SELECT " + cols + " FROM " + qTable(t.draftTable)
                + " WHERE version_id = CAST(:v AS uuid)" + onConflict;
        String deleteMissing = "DELETE FROM " + qTable(t.currentTable) + " c"
                + " WHERE NOT EXISTS (SELECT 1 FROM " + qTable(t.draftTable) + " d"
                + " WHERE d.version_id = CAST(:v AS uuid) AND " + keyMatch + ")";
        return List.of(upsert, deleteMissing);
    }

    // ── read ─────────────────────────────────────────────────────────────────────

    /** Все строки текущего PUBLISHED-снапшота ({@code __current}). */
    public List<Map<String, Object>> listCurrentRows(UUID codesetId) {
        ResolvedTable t = requireProvisioned(codesetId);
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + qTable(t.currentTable)).mapToMap().list());
    }

    /** Все строки черновика версии ({@code __draft WHERE version_id}). */
    public List<Map<String, Object>> listDraftRows(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(t.draftTable) + " WHERE version_id = CAST(:v AS uuid)")
                .bind("v", versionId)
                .mapToMap()
                .list());
    }

    // ── read-path → CodeItemDto (Stage 3) ─────────────────────────────────────────

    /**
     * PUBLISHED-снапшот ({@code __current}), спроецированный в канонический {@link CodeItemDto}
     * (динамический SELECT → DTO). Демонстрирует, что relational store воспроизводит API-контракт
     * `code_item` целиком (key_parts/attributes/parent_key/label/description/order/status/effective).
     */
    public List<CodeItemDto> listCurrentItems(UUID codesetId) {
        ResolvedTable t = requireProvisioned(codesetId);
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(t.currentTable) + orderByClause(t))
                .mapToMap()
                .list());
        return projectRows(t, null, rows);
    }

    /** Черновик версии ({@code __draft WHERE version_id}), спроецированный в {@link CodeItemDto}. */
    public List<CodeItemDto> listDraftItems(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(t.draftTable)
                                + " WHERE version_id = CAST(:v AS uuid)" + orderByClause(t))
                .bind("v", versionId)
                .mapToMap()
                .list());
        return projectRows(t, versionId, rows);
    }

    /**
     * Снимок конкретной PUBLISHED-версии ({@code __history WHERE version_id}) → {@link CodeItemDto}.
     * В отличие от {@code __current} (только последняя), история хранит все опубликованные версии —
     * это то, что нужно distribution'у для произвольного semver / {@code knowledge_as_of} (Stage 7a).
     */
    public List<CodeItemDto> listPublishedItems(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(t.historyTable)
                                + " WHERE version_id = CAST(:v AS uuid)" + orderByClause(t))
                .bind("v", versionId)
                .mapToMap()
                .list());
        return projectRows(t, versionId, rows);
    }

    /** Детерминированный порядок: order_index (NULLS LAST), затем ключи. */
    private String orderByClause(ResolvedTable t) {
        StringBuilder sb = new StringBuilder(" ORDER BY ")
                .append(RelationalDdlBuilder.q("order_index")).append(" ASC NULLS LAST");
        for (String key : t.keyNames) {
            sb.append(", ").append(RelationalDdlBuilder.q(key));
        }
        return sb.toString();
    }

    private List<CodeItemDto> projectRows(ResolvedTable t, UUID versionId, List<Map<String, Object>> rows) {
        List<String> attrNames = attrNames(t);
        List<CodeItemDto> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(projectRow(t.keyNames, attrNames, row, versionId, json));
        }
        return out;
    }

    /** Имена атрибутивных колонок = dataColumns без ключей и без стандартных. */
    private static List<String> attrNames(ResolvedTable t) {
        Set<String> standard = RelationalDdlBuilder.standardColumnNames();
        List<String> out = new ArrayList<>();
        for (Column c : t.dataColumns) {
            if (!t.keyNames.contains(c.name()) && !standard.contains(c.name())) {
                out.add(c.name());
            }
        }
        return out;
    }

    /**
     * Чистая проекция строки physical-таблицы (column to value) в {@link CodeItemDto}.
     * Static, тестируется без БД на hand-built map (как отдаёт jdbi {@code mapToMap}).
     * Поля, которых нет в relational-модели (id, system_*, row_version, parent_ref) = null.
     * jsonb-атрибуты приходят как JSON-текст (ограничение спайк-проекции).
     */
    static CodeItemDto projectRow(
            List<String> keyNames,
            List<String> attrNames,
            Map<String, Object> row,
            UUID versionId,
            ObjectMapper json) {
        List<String> keyParts = new ArrayList<>(keyNames.size());
        for (String k : keyNames) {
            keyParts.add(asString(row.get(k)));
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String a : attrNames) {
            Object v = row.get(a);
            if (v != null) {
                attributes.put(a, v);
            }
        }
        return new CodeItemDto(
                asString(row.get("id")),
                versionId == null ? null : versionId.toString(),
                keyParts,
                asString(row.get("label_ru")),
                asString(row.get("label_en")),
                asString(row.get("description_ru")),
                asString(row.get("description_en")),
                parseStringList(row.get("parent_key"), json),
                parseMap(row.get("parent_ref"), json),
                attributes,
                asInteger(row.get("order_index")),
                asString(row.get("status")),
                asString(row.get("effective_from")),
                asString(row.get("effective_to")),
                asString(row.get("system_from")),
                asString(row.get("system_to")),
                asInteger(row.get("row_version")));
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Integer asInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> parseStringList(Object v, ObjectMapper json) {
        if (v == null) {
            return null;
        }
        String text = v.toString();
        if (text.isBlank() || "null".equals(text)) {
            return null;
        }
        try {
            return json.readValue(text, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> parseMap(Object v, ObjectMapper json) {
        if (v == null) {
            return null;
        }
        String text = v.toString();
        if (text.isBlank() || "null".equals(text)) {
            return null;
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ── content_hash из rd_data (Stage 5) ─────────────────────────────────────────

    /**
     * {@code content_hash} PUBLISHED-снапшота ({@code __current}), посчитанный из физической
     * таблицы тем же алгоритмом {@link CanonicalSnapshot}, что и {@code code_item}-путь
     * ({@link bank.rdmmesh.authoring.internal.PublishedSnapshotAdapter}) — при равенстве
     * данных хэши совпадают. version_id берётся из реестра ({@code published_version_id}).
     */
    public String currentContentHash(UUID codesetId) {
        ResolvedTable t = requireProvisioned(codesetId);
        UUID published = jdbi.withExtension(PhysicalTableRegistryDao.class, d -> d.findByCodeset(codesetId))
                .map(PhysicalTableRegistryDao.PhysicalTableRow::publishedVersionId)
                .orElse(null);
        if (published == null) {
            throw new IllegalStateException("codeset " + codesetId + " ещё не публиковался в __current");
        }
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + qTable(t.currentTable)).mapToMap().list());
        return CanonicalSnapshot.contentHash(published.toString(), canonicalItems(t, rows));
    }

    /** {@code content_hash} черновика версии ({@code __draft WHERE version_id}). */
    public String draftContentHash(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(t.draftTable) + " WHERE version_id = CAST(:v AS uuid)")
                .bind("v", versionId)
                .mapToMap()
                .list());
        return CanonicalSnapshot.contentHash(versionId.toString(), canonicalItems(t, rows));
    }

    private List<Map<String, Object>> canonicalItems(ResolvedTable t, List<Map<String, Object>> rows) {
        List<String> attrNames = attrNames(t);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(canonicalItemFromRow(t, attrNames, row));
        }
        return items;
    }

    /**
     * Строка физ.таблицы → canonical-item (та же форма, что у {@code code_item}-пути).
     * Ключи стрингуются (как в jsonb-массиве key_parts); jsonb-атрибуты парсятся обратно
     * в объект; null-атрибуты опускаются (как и пустой attributes у code_item даёт {@code {}}).
     */
    private Map<String, Object> canonicalItemFromRow(
            ResolvedTable t, List<String> attrNames, Map<String, Object> row) {
        List<String> keyParts = new ArrayList<>(t.keyNames.size());
        for (String k : t.keyNames) {
            keyParts.add(asString(row.get(k)));
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String a : attrNames) {
            Object v = row.get(a);
            if (v == null) {
                continue;
            }
            attributes.put(a, "jsonb".equals(t.columnTypes.get(a))
                    ? CanonicalSnapshot.parseJson(v.toString())
                    : v);
        }
        return CanonicalSnapshot.item(
                keyParts,
                parseStringList(row.get("parent_key"), json),
                parseMap(row.get("parent_ref"), json),
                asString(row.get("label_ru")),
                asString(row.get("label_en")),
                asString(row.get("description_ru")),
                asString(row.get("description_en")),
                attributes,
                asInteger(row.get("order_index")),
                asString(row.get("status")),
                asString(row.get("effective_from")),
                asString(row.get("effective_to")));
    }

    // ── настоящие FK между __current (Stage 6, E25 column_refs) ───────────────────

    /**
     * Материализует настоящие {@code FOREIGN KEY} между {@code __current}-таблицами из
     * cross-codeset связей E25 ({@code column_refs}). Каждая связь применяется, только если:
     * (1) {@code from_column} — колонка этого справочника; (2) целевой справочник provisioned;
     * (3) {@code to_column} — единственная ключевая (PK) колонка целевого {@code __current}
     * (Postgres FK требует unique-констрейнт на цели; составной PK одной колонкой не покрыть).
     * Остальные связи тихо пропускаются с причиной (E25 — graceful degradation).
     */
    public ForeignKeyReport applyForeignKeys(UUID codesetId) {
        ResolvedTable from = requireProvisioned(codesetId);
        List<AppliedFk> applied = new ArrayList<>();
        List<SkippedFk> skipped = new ArrayList<>();
        for (CatalogReadPort.CodeSetReferenceSnapshot ref : catalog.referencesOf(codesetId)) {
            if (!from.columnTypes.containsKey(ref.fromColumn())) {
                skipped.add(new SkippedFk(ref.fromColumn(), ref.toCodesetId(),
                        "from_column не является колонкой справочника"));
                continue;
            }
            boolean targetProvisioned = jdbi.withExtension(PhysicalTableRegistryDao.class,
                    d -> d.findByCodeset(ref.toCodesetId())).isPresent();
            if (!targetProvisioned) {
                skipped.add(new SkippedFk(ref.fromColumn(), ref.toCodesetId(),
                        "целевой справочник не материализован (provision)"));
                continue;
            }
            ResolvedTable to;
            try {
                to = resolve(ref.toCodesetId());
            } catch (RuntimeException e) {
                skipped.add(new SkippedFk(ref.fromColumn(), ref.toCodesetId(),
                        "целевой справочник не резолвится: " + e.getMessage()));
                continue;
            }
            if (to.keyNames.size() != 1 || !to.keyNames.get(0).equals(ref.toColumn())) {
                skipped.add(new SkippedFk(ref.fromColumn(), ref.toCodesetId(),
                        "to_column не единственная PK-колонка целевого __current (нужен unique)"));
                continue;
            }
            String name = RelationalDdlBuilder.foreignKeyName(
                    from.currentTable, ref.fromColumn(), to.currentTable, ref.toColumn());
            String ddl = RelationalDdlBuilder.addForeignKey(
                    SCHEMA, from.currentTable, ref.fromColumn(), to.currentTable, ref.toColumn(), name);
            try {
                jdbi.useHandle(h -> h.execute(ddl));
                applied.add(new AppliedFk(
                        ref.fromColumn(), ref.toCodesetId(), to.currentTable, ref.toColumn(), name));
            } catch (RuntimeException e) {
                skipped.add(new SkippedFk(ref.fromColumn(), ref.toCodesetId(),
                        "DDL не применился (тип/данные?): " + e.getMessage()));
            }
        }
        log.info("relational store: FK для codeset_id={}: applied={} skipped={}",
                codesetId, applied.size(), skipped.size());
        return new ForeignKeyReport(applied, skipped);
    }

    // ── колоночный diff (Stage 5) ─────────────────────────────────────────────────

    /**
     * Колоночный diff двух версий по строкам {@code __draft}: ADDED/REMOVED/CHANGED по ключу,
     * с перечнем изменённых колонок. Сравниваются содержательные колонки (атрибуты, label,
     * description, parent_key, parent_ref, order_index, status, effective_*); {@code version_id}
     * и system-time ({@code system_from}/{@code system_to}, per-insert now()) исключены.
     */
    public RelDiffSummary diff(UUID fromVersionId, UUID toVersionId) {
        UUID codesetId = codesetIdOf(toVersionId);
        if (!codesetId.equals(codesetIdOf(fromVersionId))) {
            throw new IllegalArgumentException("cannot diff across codesets");
        }
        ResolvedTable t = requireProvisioned(codesetId);
        List<Map<String, Object>> from = rowsForVersion(t, fromVersionId);
        List<Map<String, Object>> to = rowsForVersion(t, toVersionId);
        return diffRows(t.keyNames, diffColumns(t), from, to);
    }

    /** Строки версии: из {@code __draft} (если есть), иначе из {@code __history} (PUBLISHED). */
    private List<Map<String, Object>> rowsForVersion(ResolvedTable t, UUID versionId) {
        List<Map<String, Object>> draft = rawRows(t.draftTable, versionId);
        return draft.isEmpty() ? rawRows(t.historyTable, versionId) : draft;
    }

    private List<Map<String, Object>> rawRows(String table, UUID versionId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(table) + " WHERE version_id = CAST(:v AS uuid)")
                .bind("v", versionId)
                .mapToMap()
                .list());
    }

    /** Содержательные колонки для diff: dataColumns без ключей, system_*, id, row_version. */
    private static final Set<String> NON_CONTENT_COLUMNS =
            Set.of("system_from", "system_to", "id", "row_version");

    private static List<String> diffColumns(ResolvedTable t) {
        List<String> out = new ArrayList<>();
        for (Column c : t.dataColumns) {
            String n = c.name();
            if (!t.keyNames.contains(n) && !NON_CONTENT_COLUMNS.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /** Чистое ядро diff'а — тестируется без БД на hand-built строках. */
    static RelDiffSummary diffRows(
            List<String> keyNames,
            List<String> diffColumns,
            List<Map<String, Object>> fromRows,
            List<Map<String, Object>> toRows) {
        Map<List<String>, Map<String, Object>> from = indexByKey(keyNames, fromRows);
        Map<List<String>, Map<String, Object>> to = indexByKey(keyNames, toRows);
        int added = 0;
        int changed = 0;
        int removed = 0;
        List<RelDiffEntry> entries = new ArrayList<>();
        for (Map.Entry<List<String>, Map<String, Object>> e : to.entrySet()) {
            Map<String, Object> before = from.get(e.getKey());
            if (before == null) {
                added++;
                entries.add(new RelDiffEntry("ADDED", e.getKey(), List.of()));
            } else {
                List<String> cols = changedColumns(diffColumns, before, e.getValue());
                if (!cols.isEmpty()) {
                    changed++;
                    entries.add(new RelDiffEntry("CHANGED", e.getKey(), cols));
                }
            }
        }
        for (Map.Entry<List<String>, Map<String, Object>> e : from.entrySet()) {
            if (!to.containsKey(e.getKey())) {
                removed++;
                entries.add(new RelDiffEntry("REMOVED", e.getKey(), List.of()));
            }
        }
        return new RelDiffSummary(added, changed, removed, entries);
    }

    private static Map<List<String>, Map<String, Object>> indexByKey(
            List<String> keyNames, List<Map<String, Object>> rows) {
        Map<List<String>, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<String> key = new ArrayList<>(keyNames.size());
            for (String k : keyNames) {
                key.add(asString(row.get(k)));
            }
            out.put(key, row);
        }
        return out;
    }

    private static List<String> changedColumns(
            List<String> diffColumns, Map<String, Object> before, Map<String, Object> after) {
        List<String> cols = new ArrayList<>();
        for (String c : diffColumns) {
            String a = asString(before.get(c));
            String b = asString(after.get(c));
            if (a == null ? b != null : !a.equals(b)) {
                cols.add(c);
            }
        }
        return cols;
    }

    // ── иерархия: closure + cycle-detection (Stage 4-full) ────────────────────────

    /** Closure иерархии PUBLISHED-снапшота ({@code __current}) по {@code parent_key}. */
    public List<ClosureRow> currentClosure(UUID codesetId) {
        ResolvedTable t = requireProvisioned(codesetId);
        return runClosure(RelationalDdlBuilder.closureQuery(SCHEMA, t.currentTable, t.keyNames, false), null);
    }

    /** Closure иерархии черновика версии ({@code __draft WHERE version_id}). */
    public List<ClosureRow> draftClosure(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        return runClosure(
                RelationalDdlBuilder.closureQuery(SCHEMA, t.draftTable, t.keyNames, true), versionId);
    }

    /** Ключи, участвующие в цикле {@code parent_key}, в PUBLISHED-снапшоте ({@code __current}). */
    public List<List<String>> currentCycles(UUID codesetId) {
        ResolvedTable t = requireProvisioned(codesetId);
        return runCycles(RelationalDdlBuilder.cycleDetectionQuery(SCHEMA, t.currentTable, t.keyNames, false), null);
    }

    /** Ключи, участвующие в цикле {@code parent_key}, в черновике версии. */
    public List<List<String>> draftCycles(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        return runCycles(
                RelationalDdlBuilder.cycleDetectionQuery(SCHEMA, t.draftTable, t.keyNames, true), versionId);
    }

    private List<ClosureRow> runClosure(String sql, UUID versionId) {
        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql);
            if (versionId != null) {
                q.bind("v", versionId);
            }
            return q.map((rs, ctx) -> new ClosureRow(
                            parseStringList(rs.getString("ancestor_key"), json),
                            parseStringList(rs.getString("descendant_key"), json),
                            rs.getInt("depth")))
                    .list();
        });
    }

    private List<List<String>> runCycles(String sql, UUID versionId) {
        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql);
            if (versionId != null) {
                q.bind("v", versionId);
            }
            return q.map((rs, ctx) -> parseStringList(rs.getString("self_key"), json)).list();
        });
    }

    // ── authoring write-flip (Stage 7c): rd_data — источник истины ─────────────────

    /** INSERT новой строки draft (новый id, row_version=0). Дубликат ключа → IllegalArgumentException. */
    public CodeItemDto insertDraftItem(
            UUID versionId, List<String> keyParts, Map<String, Object> attributes, List<String> parentKey,
            Map<String, Object> parentRef, String labelRu, String labelEn, String descriptionRu,
            String descriptionEn, Integer orderIndex, String status, String effectiveFrom, String effectiveTo) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        Map<String, Object> cells = itemCells(t, keyParts, attributes, parentKey, parentRef, labelRu, labelEn,
                descriptionRu, descriptionEn, orderIndex, status, effectiveFrom, effectiveTo, null, null);
        validateCells(t, cells);
        UUID id = UUID.randomUUID();
        cells.put("id", id);
        cells.put("row_version", 0);
        try {
            jdbi.useHandle(h -> insertRow(h, t, versionId, cells));
        } catch (UnableToExecuteStatementException e) {
            if (isUniqueViolation(e)) {
                throw new IllegalArgumentException(
                        "Item with key " + keyParts + " already exists in this version");
            }
            throw e;
        }
        return findDraftItemById(versionId, id).orElseThrow();
    }

    /**
     * UPDATE строки draft по {@code id} с optimistic-lock (CAS по {@code row_version}).
     * Возвращает число затронутых строк (0 = нет совпадения: либо нет строки, либо stale).
     * Передаются уже смерженные значения (caller отвечает за merge patch+current).
     */
    public int updateDraftItemById(
            UUID versionId, UUID itemId, int expectedRowVersion, Map<String, Object> attributes,
            List<String> parentKey, Map<String, Object> parentRef, String labelRu, String labelEn,
            String descriptionRu, String descriptionEn, Integer orderIndex, String status,
            String effectiveFrom, String effectiveTo) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        Map<String, Object> cells = dataCells(t, attributes, parentKey, parentRef, labelRu, labelEn,
                descriptionRu, descriptionEn, orderIndex, status, effectiveFrom, effectiveTo);
        StringBuilder set = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, Object> e : cells.entrySet()) {
            String name = e.getKey();
            String type = t.columnTypes.get(name);
            String p = "p" + i++;
            if (set.length() > 0) set.append(", ");
            set.append(RelationalDdlBuilder.q(name)).append(" = CAST(:").append(p)
                    .append(" AS ").append(type).append(')');
            binds.put(p, coerce(e.getValue(), type));
        }
        if (set.length() > 0) set.append(", ");
        set.append(RelationalDdlBuilder.q("row_version")).append(" = ")
                .append(RelationalDdlBuilder.q("row_version")).append(" + 1");
        String sql = "UPDATE " + qTable(t.draftTable) + " SET " + set
                + " WHERE version_id = CAST(:v AS uuid)"
                + " AND " + RelationalDdlBuilder.q("id") + " = CAST(:id AS uuid)"
                + " AND " + RelationalDdlBuilder.q("row_version") + " = :rv";
        return jdbi.withHandle(h -> {
            var u = h.createUpdate(sql);
            binds.forEach(u::bind);
            return u.bind("v", versionId).bind("id", itemId).bind("rv", expectedRowVersion).execute();
        });
    }

    /** DELETE строки draft по id. */
    public boolean deleteDraftItemById(UUID versionId, UUID itemId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        int n = jdbi.withHandle(h -> h.createUpdate("DELETE FROM " + qTable(t.draftTable)
                        + " WHERE version_id = CAST(:v AS uuid) AND " + RelationalDdlBuilder.q("id")
                        + " = CAST(:id AS uuid)")
                .bind("v", versionId).bind("id", itemId).execute());
        return n > 0;
    }

    /** DELETE всех строк draft версии; возвращает количество. */
    public int clearDraft(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        return jdbi.withHandle(h -> h.createUpdate(
                        "DELETE FROM " + qTable(t.draftTable) + " WHERE version_id = CAST(:v AS uuid)")
                .bind("v", versionId).execute());
    }

    /** Страница строк draft версии → CodeItemDto. */
    public List<CodeItemDto> listDraftItemsPage(UUID versionId, int offset, int size) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(t.draftTable)
                                + " WHERE version_id = CAST(:v AS uuid)" + orderByClause(t)
                                + " OFFSET :off LIMIT :lim")
                .bind("v", versionId).bind("off", offset).bind("lim", size)
                .mapToMap().list());
        return projectRows(t, versionId, rows);
    }

    /** Число строк draft версии. */
    public int countDraftItems(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT count(*) FROM " + qTable(t.draftTable) + " WHERE version_id = CAST(:v AS uuid)")
                .bind("v", versionId).mapTo(Integer.class).one());
    }

    /** Поиск строки draft по id. */
    public Optional<CodeItemDto> findDraftItemById(UUID versionId, UUID itemId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + qTable(t.draftTable)
                        + " WHERE version_id = CAST(:v AS uuid) AND " + RelationalDdlBuilder.q("id")
                        + " = CAST(:id AS uuid)")
                .bind("v", versionId).bind("id", itemId).mapToMap().findOne())
                .map(row -> projectRow(t.keyNames, attrNames(t), row, versionId, json));
    }

    /** Поиск строки draft по ключу. */
    public Optional<CodeItemDto> findDraftItemByKey(UUID versionId, List<String> keyParts) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        Map<String, Object> keyCells = keyCellsOf(t, keyParts);
        StringBuilder where = new StringBuilder("version_id = CAST(:v AS uuid)");
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("v", versionId);
        int i = 0;
        for (String key : t.keyNames) {
            String p = "k" + i++;
            where.append(" AND ").append(RelationalDdlBuilder.q(key))
                    .append(" = CAST(:").append(p).append(" AS ").append(t.columnTypes.get(key)).append(')');
            binds.put(p, coerce(keyCells.get(key), t.columnTypes.get(key)));
        }
        String sql = "SELECT * FROM " + qTable(t.draftTable) + " WHERE " + where;
        return jdbi.withHandle(h -> {
            var q = h.createQuery(sql);
            binds.forEach(q::bind);
            return q.mapToMap().findOne();
        }).map(row -> projectRow(t.keyNames, attrNames(t), row, versionId, json));
    }

    /**
     * Клон items опубликованной версии {@code baseVersionId} (из {@code __history}) в draft новой
     * версии {@code newVersionId}: новые id, row_version=0. {@code codesetId} передаётся явно —
     * {@code newVersionId} может быть ещё не закоммичен (вызов из createDraft-tx). Возвращает число строк.
     */
    public int cloneDraftFromPublished(UUID codesetId, UUID baseVersionId, UUID newVersionId) {
        ResolvedTable t = requireProvisioned(codesetId);
        StringBuilder cols = new StringBuilder();
        for (Column c : t.dataColumns) {
            if ("id".equals(c.name()) || "row_version".equals(c.name())) {
                continue;
            }
            if (cols.length() > 0) cols.append(", ");
            cols.append(RelationalDdlBuilder.q(c.name()));
        }
        String vid = RelationalDdlBuilder.q("version_id");
        String idCol = RelationalDdlBuilder.q("id");
        String rv = RelationalDdlBuilder.q("row_version");
        String sql = "INSERT INTO " + qTable(t.draftTable) + " (" + vid + ", " + idCol + ", " + rv + ", " + cols + ")"
                + " SELECT CAST(:new AS uuid), gen_random_uuid(), 0, " + cols
                + " FROM " + qTable(t.historyTable) + " WHERE version_id = CAST(:base AS uuid)";
        return jdbi.withHandle(h -> h.createUpdate(sql)
                .bind("new", newVersionId).bind("base", baseVersionId).execute());
    }

    /** Plain INSERT строки (без ON CONFLICT) — version_id биндится отдельно. */
    private void insertRow(Handle h, ResolvedTable t, UUID versionId, Map<String, Object> cells) {
        StringBuilder cols = new StringBuilder(RelationalDdlBuilder.q("version_id"));
        StringBuilder vals = new StringBuilder("CAST(:pv AS uuid)");
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("pv", versionId);
        int i = 0;
        for (Map.Entry<String, Object> e : cells.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String name = e.getKey();
            String type = t.columnTypes.get(name);
            String p = "p" + i++;
            cols.append(", ").append(RelationalDdlBuilder.q(name));
            vals.append(", CAST(:").append(p).append(" AS ").append(type).append(')');
            binds.put(p, coerce(e.getValue(), type));
        }
        String sql = "INSERT INTO " + qTable(t.draftTable) + " (" + cols + ") VALUES (" + vals + ')';
        var u = h.createUpdate(sql);
        binds.forEach(u::bind);
        u.execute();
    }

    /** Canonical bytes версии из {@code __draft} (для content_hash/подписи; publishing читает до publish'а). */
    public byte[] canonicalBytes(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(t.draftTable) + " WHERE version_id = CAST(:v AS uuid)")
                .bind("v", versionId).mapToMap().list());
        return CanonicalSnapshot.bytes(versionId.toString(), canonicalItems(t, rows));
    }

    private static boolean isUniqueViolation(UnableToExecuteStatementException e) {
        return e.getCause() instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState());
    }

    // ── row write helper ─────────────────────────────────────────────────────────

    private void validateCells(ResolvedTable t, Map<String, Object> cells) {
        if (cells == null || cells.isEmpty()) {
            throw new IllegalArgumentException("row is empty");
        }
        for (String name : cells.keySet()) {
            if (!t.columnTypes.containsKey(name)) {
                throw new IllegalArgumentException("unknown column: " + name);
            }
        }
        for (String key : t.keyNames) {
            if (cells.get(key) == null) {
                throw new IllegalArgumentException("key column is required: " + key);
            }
        }
    }

    /** INSERT ... ON CONFLICT (version_id, ключи) для одной строки черновика. */
    private void upsertDraft(Handle h, ResolvedTable t, UUID versionId, Map<String, Object> cells) {
        StringBuilder cols = new StringBuilder(RelationalDdlBuilder.q("version_id"));
        StringBuilder vals = new StringBuilder("CAST(:pv AS uuid)");
        StringBuilder updates = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("pv", versionId);
        int i = 0;
        for (Map.Entry<String, Object> e : cells.entrySet()) {
            Object value = e.getValue();
            if (value == null) {
                continue;
            }
            String name = e.getKey();
            String type = t.columnTypes.get(name);
            String p = "p" + i++;
            cols.append(", ").append(RelationalDdlBuilder.q(name));
            vals.append(", CAST(:").append(p).append(" AS ").append(type).append(')');
            if (!t.keyNames.contains(name)) {
                if (updates.length() > 0) updates.append(", ");
                updates.append(RelationalDdlBuilder.q(name))
                        .append(" = EXCLUDED.").append(RelationalDdlBuilder.q(name));
            }
            binds.put(p, coerce(value, type));
        }

        StringBuilder keyList = new StringBuilder(RelationalDdlBuilder.q("version_id"));
        for (String key : t.keyNames) {
            keyList.append(", ").append(RelationalDdlBuilder.q(key));
        }
        String onConflict = updates.length() == 0
                ? " ON CONFLICT (" + keyList + ") DO NOTHING"
                : " ON CONFLICT (" + keyList + ") DO UPDATE SET " + updates;

        String sql = "INSERT INTO " + qTable(t.draftTable) + " (" + cols + ") VALUES (" + vals + ')'
                + onConflict;
        var update = h.createUpdate(sql);
        binds.forEach(update::bind);
        update.execute();
    }

    private static String qTable(String table) {
        return RelationalDdlBuilder.q(SCHEMA) + '.' + RelationalDdlBuilder.q(table);
    }

    // ── resolve ───────────────────────────────────────────────────────────────

    private UUID codesetIdOf(UUID versionId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT codeset_id FROM authoring.code_set_version WHERE id = :v")
                .bind("v", versionId)
                .mapTo(UUID.class)
                .findOne())
                .orElseThrow(() -> new IllegalArgumentException("unknown version: " + versionId));
    }

    private ResolvedTable requireProvisioned(UUID codesetId) {
        Optional<PhysicalTableRegistryDao.PhysicalTableRow> reg = jdbi.withExtension(
                PhysicalTableRegistryDao.class, dao -> dao.findByCodeset(codesetId));
        if (reg.isEmpty()) {
            throw new IllegalStateException(
                    "codeset " + codesetId + " is not provisioned — call provision() first");
        }
        return resolve(codesetId);
    }

    /** Как {@link #requireProvisioned}, но при отсутствии — лениво материализует (idempotent). */
    private ResolvedTable ensureProvisioned(UUID codesetId) {
        boolean exists = jdbi.withExtension(PhysicalTableRegistryDao.class,
                dao -> dao.findByCodeset(codesetId)).isPresent();
        if (!exists) {
            provision(codesetId);
        }
        return resolve(codesetId);
    }

    /** Снимок CodeSet'а → имена таблиц и типизированные колонки. */
    private ResolvedTable resolve(UUID codesetId) {
        CatalogReadPort.CodeSetSnapshot cs = catalog.findCodeSet(codesetId)
                .filter(s -> !s.deleted())
                .orElseThrow(() -> new IllegalArgumentException("unknown codeset: " + codesetId));
        String domainName = catalog.findDomain(cs.domainId())
                .map(CatalogReadPort.DomainSnapshot::name)
                .orElseThrow(() -> new IllegalStateException("domain not found: " + cs.domainId()));
        String base = RelationalDdlBuilder.tableName(domainName, cs.name());

        List<Column> keyColumns = parseKeyColumns(cs.keySpecJson());
        List<Column> attributeColumns = catalog.currentSchema(codesetId)
                .map(s -> parseAttributeColumns(s.jsonSchemaText()))
                .orElseGet(List::of);

        List<Column> keyAndAttr = new ArrayList<>(keyColumns);
        keyAndAttr.addAll(attributeColumns);
        List<Column> dataColumns = RelationalDdlBuilder.withStandard(keyAndAttr);

        Map<String, String> types = new LinkedHashMap<>();
        for (Column c : dataColumns) {
            types.put(c.name(), c.sqlType());
        }
        List<String> keyNames = new ArrayList<>();
        for (Column c : keyColumns) {
            keyNames.add(c.name());
        }

        return new ResolvedTable(
                base,
                RelationalDdlBuilder.draftTable(base),
                RelationalDdlBuilder.currentTable(base),
                RelationalDdlBuilder.historyTable(base),
                cs.schemaVersion(),
                dataColumns,
                types,
                keyNames);
    }

    private List<Column> parseKeyColumns(String keySpecJson) {
        if (keySpecJson == null || keySpecJson.isBlank()) {
            throw new IllegalStateException("codeset has no key_spec");
        }
        try {
            JsonNode parts = json.readTree(keySpecJson).path("parts");
            List<Column> out = new ArrayList<>();
            for (JsonNode part : parts) {
                String name = part.path("name").asText(null);
                String type = part.path("type").asText("STRING");
                out.add(new Column(name, RelationalTypes.keyPartSqlType(type), true));
            }
            if (out.isEmpty()) {
                throw new IllegalStateException("key_spec.parts is empty");
            }
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot parse key_spec: " + keySpecJson, e);
        }
    }

    private List<Column> parseAttributeColumns(String jsonSchemaText) {
        if (jsonSchemaText == null || jsonSchemaText.isBlank()) {
            return List.of();
        }
        try {
            JsonNode props = json.readTree(jsonSchemaText).path("properties");
            List<Column> out = new ArrayList<>();
            props.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode def = entry.getValue();
                String type = jsonTypeOf(def);
                String format = def.path("format").asText(null);
                boolean hasEnum = def.has("enum");
                out.add(new Column(name, RelationalTypes.jsonSchemaSqlType(type, format, hasEnum), false));
            });
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot parse json_schema: " + jsonSchemaText, e);
        }
    }

    private static String jsonTypeOf(JsonNode def) {
        JsonNode type = def.path("type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (!"null".equals(t.asText())) {
                    return t.asText();
                }
            }
            return "string";
        }
        return type.asText("string");
    }

    /** Item-поля (keyParts позиционно + атрибуты + label/status/effective) → map колонка→значение. */
    private Map<String, Object> itemCells(
            ResolvedTable t,
            List<String> keyParts,
            Map<String, Object> attributes,
            List<String> parentKey,
            Map<String, Object> parentRef,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Integer orderIndex,
            String status,
            String effectiveFrom,
            String effectiveTo,
            String systemFrom,
            String systemTo) {
        Map<String, Object> cells = keyCellsOf(t, keyParts);
        cells.putAll(dataCells(t, attributes, parentKey, parentRef, labelRu, labelEn,
                descriptionRu, descriptionEn, orderIndex, status, effectiveFrom, effectiveTo));
        putIfNotNull(cells, "system_from", systemFrom);
        putIfNotNull(cells, "system_to", systemTo);
        return cells;
    }

    /** Не-ключевые содержательные колонки (атрибуты + label/desc/parent/order/status/effective). */
    private Map<String, Object> dataCells(
            ResolvedTable t,
            Map<String, Object> attributes,
            List<String> parentKey,
            Map<String, Object> parentRef,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Integer orderIndex,
            String status,
            String effectiveFrom,
            String effectiveTo) {
        Map<String, Object> cells = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((name, value) -> {
                if (t.columnTypes.containsKey(name) && !t.keyNames.contains(name)) {
                    cells.put(name, value);
                }
            });
        }
        // parent_key/parent_ref — jsonb (coerce сериализует List/Map в JSON-текст).
        putIfNotNull(cells, "parent_key", parentKey);
        putIfNotNull(cells, "parent_ref", parentRef);
        putIfNotNull(cells, "label_ru", labelRu);
        putIfNotNull(cells, "label_en", labelEn);
        putIfNotNull(cells, "description_ru", descriptionRu);
        putIfNotNull(cells, "description_en", descriptionEn);
        putIfNotNull(cells, "order_index", orderIndex);
        putIfNotNull(cells, "status", status);
        putIfNotNull(cells, "effective_from", effectiveFrom);
        putIfNotNull(cells, "effective_to", effectiveTo);
        return cells;
    }

    /** Позиционная раскладка {@code keyParts} в map ключевая_колонка→значение. */
    private Map<String, Object> keyCellsOf(ResolvedTable t, List<String> keyParts) {
        if (keyParts == null || keyParts.size() != t.keyNames.size()) {
            throw new IllegalArgumentException("key_parts arity "
                    + (keyParts == null ? "null" : keyParts.size())
                    + " != key columns " + t.keyNames.size());
        }
        Map<String, Object> cells = new LinkedHashMap<>();
        for (int i = 0; i < t.keyNames.size(); i++) {
            cells.put(t.keyNames.get(i), keyParts.get(i));
        }
        return cells;
    }

    private static void putIfNotNull(Map<String, Object> cells, String key, Object value) {
        if (value != null) {
            cells.put(key, value);
        }
    }

    /** jsonb-колонки сериализуем в JSON-текст; остальное — как есть. */
    private Object coerce(Object value, String sqlType) {
        if ("jsonb".equals(sqlType) && !(value instanceof String)) {
            try {
                return json.writeValueAsString(value);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("cannot serialise jsonb cell", e);
            }
        }
        return value;
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    private record ResolvedTable(
            String base,
            String draftTable,
            String currentTable,
            String historyTable,
            int schemaVersion,
            List<Column> dataColumns,
            Map<String, String> columnTypes,
            List<String> keyNames) {}

    public record ProvisionResult(
            String schema, String draftTable, String currentTable, String historyTable,
            List<String> columns) {}

    public record PublishResult(UUID codesetId, String currentTable, int rowsPublished) {}

    /** Пара closure-иерархии: предок → потомок на расстоянии {@code depth} рёбер. */
    public record ClosureRow(List<String> ancestorKey, List<String> descendantKey, int depth) {}

    /** Запись колоночного diff'а: {@code op} ∈ ADDED/REMOVED/CHANGED, ключ, изменённые колонки. */
    public record RelDiffEntry(String op, List<String> keyParts, List<String> changedColumns) {}

    /** Сводка колоночного diff'а двух версий. */
    public record RelDiffSummary(int added, int changed, int removed, List<RelDiffEntry> entries) {}

    /** Применённый FK: from_column → toTable(to_column), имя констрейнта. */
    public record AppliedFk(
            String fromColumn, UUID toCodesetId, String toTable, String toColumn, String constraint) {}

    /** Пропущенная связь и причина (E25 graceful degradation). */
    public record SkippedFk(String fromColumn, UUID toCodesetId, String reason) {}

    /** Результат материализации FK справочника. */
    public record ForeignKeyReport(List<AppliedFk> applied, List<SkippedFk> skipped) {}

}
