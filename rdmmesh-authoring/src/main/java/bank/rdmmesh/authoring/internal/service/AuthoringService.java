package bank.rdmmesh.authoring.internal.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.VersionDeletedDomainEvent;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.CatalogReadPort.CodeSetSchemaSnapshot;
import bank.rdmmesh.api.port.CatalogReadPort.CodeSetSnapshot;
import bank.rdmmesh.authoring.internal.AuthoringMappers;
import bank.rdmmesh.authoring.internal.SemVer;
import bank.rdmmesh.authoring.internal.csv.CsvBulkParser;
import bank.rdmmesh.authoring.internal.dao.CodeSetVersionDao;
import bank.rdmmesh.authoring.internal.dao.CodeSetVersionDao.VersionRow;
import bank.rdmmesh.authoring.internal.diff.DiffCalculator;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService;
import bank.rdmmesh.authoring.internal.validation.AttributesValidator;
import bank.rdmmesh.authoring.internal.xlsx.MatrixPivotSheetParser;
import bank.rdmmesh.authoring.internal.xlsx.XlsxBulkParser;
import bank.rdmmesh.authoring.resource.CodeItemDto;
import bank.rdmmesh.spec.entity.CodeSetVersion;

/**
 * Бизнес-операции authoring'а. Единственное место, которое пишет в schema {@code authoring}.
 * Ports / DAO ниже — детали реализации, наружу торчит публичный API сервиса.
 *
 * <p><b>Инварианты:</b>
 * <ul>
 *   <li>CodeSetVersion переходит из DRAFT только через WorkflowPort (E5) — здесь только
 *       создание DRAFT'а и редактирование внутри него. Service не берётся переводить
 *       статус сам.
 *   <li>Все mutations CodeItem'ов проверяют, что у version'а статус DRAFT — {@code updateInDraft},
 *       {@code deleteInDraft} в DAO зашивают это в WHERE-clause; для INSERT'а проверка
 *       идёт явно в service'е.
 *   <li>Активная schema_version фиксируется в {@code code_set_version.schema_version} при
 *       создании draft'а и используется для валидации attributes до самого publish'а.
 *       Это значит: даже если Schema Designer выпустит новую schema-revision посреди
 *       работы Author'а — текущий draft валидируется по «своей», зафиксированной
 *       на момент создания.
 *   <li>{@code row_version} — optimistic lock. Клиент шлёт expected_row_version, service
 *       выполняет conditional UPDATE. 0 affected rows → {@link OptimisticLockException}.
 * </ul>
 */
public final class AuthoringService {

    private static final Logger log = LoggerFactory.getLogger(AuthoringService.class);

    private final Jdbi jdbi;
    private final CatalogReadPort catalog;
    private final ObjectMapper json;
    private final AttributesValidator validator;
    private final CsvBulkParser csv;
    private final XlsxBulkParser xlsx;
    private final EventBus eventBus;

    /**
     * Relational store (Stage 2-final, nullable). Когда задан — каждая mutation item'а
     * best-effort зеркалируется в {@code rd_data."<base>__draft"} после успешной записи
     * в {@code code_item}. {@code code_item} остаётся источником истины; зеркало — путь
     * к тому, чтобы {@code __draft}/{@code __current} стали единственным стором (спайк).
     */
    private final RelationalStoreService relationalStore;

    public AuthoringService(Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json) {
        this(jdbi, catalog, json, null, null);
    }

    public AuthoringService(Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json,
                            EventBus eventBus) {
        this(jdbi, catalog, json, eventBus, null);
    }

    public AuthoringService(Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json,
                            EventBus eventBus, RelationalStoreService relationalStore) {
        this.jdbi = jdbi;
        this.catalog = catalog;
        this.json = json;
        this.validator = new AttributesValidator(json);
        this.csv = new CsvBulkParser(json);
        this.xlsx = new XlsxBulkParser(json);
        this.eventBus = eventBus;
        this.relationalStore = relationalStore;
    }

    // ── Versions ────────────────────────────────────────────────────────────────

    public List<CodeSetVersion> listVersions(UUID codesetId) {
        return jdbi.withExtension(CodeSetVersionDao.class, dao -> dao.findByCodeset(codesetId).stream()
                .map(AuthoringMappers::toVersion)
                .toList());
    }

    public Optional<CodeSetVersion> findVersion(UUID versionId) {
        return jdbi.withExtension(CodeSetVersionDao.class, dao -> dao.findById(versionId))
                .map(AuthoringMappers::toVersion);
    }

    /**
     * Создаёт DRAFT-версию из последней published. Если её нет — пустой draft с базовой
     * версией {@code 0.1.0}. Если клиент явно указал {@code requestedVersion} — берём её,
     * иначе — bump (default — minor) от последней published.
     *
     * <p>В одной транзакции: INSERT в {@code code_set_version} + (если есть base)
     * SELECT-INSERT items из base-версии + SET item_count. Closure-table обслуживается
     * триггерами {@code code_item_closure_*} (миграция V022) — incremental update.
     */
    public CodeSetVersion createDraft(
            UUID codesetId, String requestedVersion, String bump, String releaseChannel, UUID createdBy) {

        CodeSetSnapshot codeSet = catalog.findCodeSet(codesetId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown codeset: " + codesetId));
        if (codeSet.deleted()) {
            throw new IllegalArgumentException("CodeSet is deleted: " + codesetId);
        }

        return jdbi.inTransaction(handle -> {
            CodeSetVersionDao versionDao = handle.attach(CodeSetVersionDao.class);

            // SPEC §2.2 — at-most-one открытый цикл на CodeSet. Любая non-terminal версия
            // (DRAFT/IN_REVIEW/STEWARD_APPROVED/OWNER_APPROVED) блокирует новый draft.
            // Ограничение поддерживается только здесь — workflow rejects/transitions
            // освобождают слот, переводя версию в DRAFT (откат) или PUBLISHED/DEPRECATED/REJECTED (terminal).
            List<VersionRow> open = versionDao.findOpenVersions(codesetId);
            if (!open.isEmpty()) {
                VersionRow blocking = open.get(0);
                throw new IllegalArgumentException("CodeSet " + codesetId + " уже имеет открытую версию "
                        + blocking.version() + " (" + blocking.status() + ")."
                        + " Завершите её цикл (publish/deprecate/reject) перед созданием новой.");
            }

            Optional<VersionRow> base = versionDao.findLatestPublished(codesetId);
            String version;
            if (requestedVersion != null) {
                if (!SemVer.isValid(requestedVersion)) {
                    throw new IllegalArgumentException("Bad semver: " + requestedVersion);
                }
                version = requestedVersion;
            } else {
                version = SemVer.nextFor(base.map(VersionRow::version).orElse(null), bump);
            }

            // Запрещаем DRAFT поверх существующей версии с тем же semver.
            if (versionDao.findByCodesetAndVersion(codesetId, version).isPresent()) {
                throw new IllegalArgumentException("Version " + version + " already exists for codeset " + codesetId);
            }

            UUID newId = UUID.randomUUID();
            int n = versionDao.insertDraft(
                    newId, codesetId, version, codeSet.schemaVersion(), releaseChannel, createdBy);
            if (n != 1) throw new IllegalStateException("INSERT code_set_version returned " + n);

            // Stage 7c: items живут в rd_data. Материализуем таблицы и клонируем base
            // (последнюю published) из __history в __draft новой версии.
            relationalStore.provision(codesetId);
            int copied = base.map(b -> relationalStore.cloneDraftFromPublished(codesetId, b.id(), newId)).orElse(0);
            versionDao.setItemCount(newId, copied);

            log.info(
                    "authoring: создан draft codeset_id={} version={} from={} items={}",
                    codesetId,
                    version,
                    base.map(VersionRow::version).orElse("<empty>"),
                    copied);
            return AuthoringMappers.toVersion(versionDao.findById(newId).orElseThrow());
        });
    }

    public boolean deleteDraft(UUID versionId, UUID actor) {
        boolean deleted = jdbi.inTransaction(handle -> {
            // closure уйдёт через ON DELETE CASCADE (внешний ключ) с CodeSetVersion.
            int n = handle.attach(CodeSetVersionDao.class).deleteDraft(versionId);
            return n == 1;
        });
        if (deleted && eventBus != null) {
            // ПОСЛЕ commit'а: подписчик (Flowable cleanup осиротевшего
            // инстанса при engine=flowable, E16.3) + audit. Best-effort —
            // SyncEventBus изолирует исключения подписчиков (E5 §1.5),
            // удаление версии не откатывается.
            try {
                eventBus.publish(new VersionDeletedDomainEvent(
                        UUID.randomUUID(),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        versionId,
                        actor));
            } catch (RuntimeException e) {
                log.warn("authoring: VersionDeletedDomainEvent publish failed "
                        + "(version_id={}): {}", versionId, e.toString());
            }
        }
        return deleted;
    }

    // ── Items ───────────────────────────────────────────────────────────────────

    public ItemsPage listItems(UUID versionId, int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 100;
        if (size > 10_000) size = 10_000;
        int offset = page * size;
        // Stage 7c: читаем из rd_data (__draft версии).
        int total = relationalStore.countDraftItems(versionId);
        List<CodeItemDto> items = relationalStore.listDraftItemsPage(versionId, offset, size);
        return new ItemsPage(page, size, total, items);
    }

    public Optional<CodeItemDto> findItemByKey(UUID versionId, List<String> keyParts) {
        return relationalStore.findDraftItemByKey(versionId, keyParts);
    }

    public CodeItemDto addItem(UUID versionId, NewItem req, UUID author) {
        VersionContext ctx = loadDraftContext(versionId);
        validateOrThrow(ctx, req.attributes(), keyDescription(req.keyParts()));
        // Stage 7: жёсткая ссылочная целостность — значение FK-колонки должно
        // существовать в опубликованном родителе (иначе понятная ошибка сразу).
        assertReferencesOrThrow(ctx.codesetId(), req.keyParts(), req.attributes());

        // Stage 7c: пишем в rd_data (__draft). row_version/id/optimistic-lock — на колонках.
        CodeItemDto created = relationalStore.insertDraftItem(
                versionId,
                req.keyParts(),
                req.attributes() == null ? Map.of() : req.attributes(),
                req.parentKey(),
                req.parentRef(),
                req.labelRu(),
                req.labelEn(),
                req.descriptionRu(),
                req.descriptionEn(),
                req.orderIndex(),
                req.status(),
                dateText(req.effectiveFrom()),
                dateText(req.effectiveTo()));
        refreshItemCount(versionId);
        log.debug("authoring: + item version_id={} key={} by={}", versionId, req.keyParts(), author);
        return created;
    }

    public CodeItemDto updateItem(UUID versionId, UUID itemId, ItemPatch patch, UUID author) {
        VersionContext ctx = loadDraftContext(versionId);
        if (patch.attributes() != null) {
            validateOrThrow(ctx, patch.attributes(), "id=" + itemId);
        }

        // Stage 7c: читаем current из rd_data, мерджим patch, CAS по row_version.
        CodeItemDto current = relationalStore.findDraftItemById(versionId, itemId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Item not found: " + itemId + " in version " + versionId));

        // Stage 7: ссылочная целостность по итоговой (после merge) строке.
        assertReferencesOrThrow(
                ctx.codesetId(),
                current.keyParts(),
                patch.attributes() != null ? patch.attributes() : current.attributes());

        int n = relationalStore.updateDraftItemById(
                versionId,
                itemId,
                patch.expectedRowVersion(),
                patch.attributes() != null ? patch.attributes() : current.attributes(),
                patch.parentKey() != null ? patch.parentKey() : current.parentKey(),
                patch.parentRef() != null ? patch.parentRef() : current.parentRef(),
                patch.labelRu() != null ? patch.labelRu() : current.labelRu(),
                patch.labelEn() != null ? patch.labelEn() : current.labelEn(),
                patch.descriptionRu() != null ? patch.descriptionRu() : current.descriptionRu(),
                patch.descriptionEn() != null ? patch.descriptionEn() : current.descriptionEn(),
                patch.orderIndex() != null ? patch.orderIndex() : current.orderIndex(),
                patch.status() != null ? patch.status() : current.status(),
                patch.effectiveFrom() != null ? dateText(patch.effectiveFrom()) : current.effectiveFrom(),
                patch.effectiveTo() != null ? dateText(patch.effectiveTo()) : current.effectiveTo());
        if (n == 0) {
            throw new OptimisticLockException("Stale row_version for item " + itemId + ": expected "
                    + patch.expectedRowVersion() + " (current " + current.rowVersion() + ")");
        }
        log.debug("authoring: ~ item version_id={} id={} by={}", versionId, itemId, author);
        return relationalStore.findDraftItemById(versionId, itemId).orElseThrow();
    }

    public boolean deleteItem(UUID versionId, UUID itemId, UUID author) {
        loadDraftContext(versionId); // только чтобы проверить, что это DRAFT
        // Stage 7c: удаляем из rd_data (__draft) по id.
        boolean deleted = relationalStore.deleteDraftItemById(versionId, itemId);
        if (deleted) {
            refreshItemCount(versionId);
            log.debug("authoring: - item version_id={} id={} by={}", versionId, itemId, author);
        }
        return deleted;
    }

    /**
     * E21 — bulk-delete всех items в DRAFT. Используется UI-кнопкой «Очистить
     * все записи» перед повторным bulk-import'ом. Stage 7c: чистит {@code rd_data."<base>__draft"}
     * версии; {@code item_count} сбрасывается в 0.
     *
     * @return количество удалённых items (0 если версия уже была пуста)
     * @throws IllegalArgumentException если версии нет
     * @throws IllegalStateException    если версия не DRAFT
     */
    public int clearAllItems(UUID versionId, UUID actor) {
        loadDraftContext(versionId);
        // Stage 7c: чистим rd_data (__draft версии).
        int deleted = relationalStore.clearDraft(versionId);
        jdbi.useExtension(CodeSetVersionDao.class, dao -> dao.setItemCount(versionId, 0));
        log.info("authoring: clear-all items version_id={} deleted={} by={}", versionId, deleted, actor);
        return deleted;
    }

    // ── Bulk import ─────────────────────────────────────────────────────────────

    /**
     * Bulk JSON: список объектов с теми же полями, что и {@link NewItem}. Семантика
     * UPSERT по {@code key_parts}: если key уже есть в этой DRAFT-версии — обновляется,
     * иначе — добавляется. Атомарно: вся пачка применяется в одной транзакции, при
     * любой ошибке валидации откатываемся.
     */
    public BulkResult bulkUpsertJson(UUID versionId, List<NewItem> rows, UUID author) {
        VersionContext ctx = loadDraftContext(versionId);
        List<BulkError> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            NewItem r = rows.get(i);
            if (r.keyParts() == null || r.keyParts().isEmpty()) {
                errors.add(new BulkError(i, null, "key_parts", "key_parts is required"));
                continue;
            }
            List<String> errs =
                    validator.validate(ctx.codesetId(), ctx.schemaVersion(), ctx.schemaText(), r.attributes());
            for (String e : errs) errors.add(new BulkError(i, r.keyParts(), "attributes", e));
            // Stage 7: ссылочная целостность — весь импорт отклоняется, если хоть
            // одна строка ссылается на отсутствующее в родителе значение.
            for (String e : relationalStore.referenceViolations(ctx.codesetId(), r.keyParts(), r.attributes())) {
                errors.add(new BulkError(i, r.keyParts(), "reference", e));
            }
        }
        if (!errors.isEmpty()) {
            return BulkResult.rejected(rows.size(), errors);
        }
        // Stage 7c: upsert по ключу прямо в rd_data (__draft). UPSERT-семантика
        // (есть ключ → update, нет → insert) реализуется find+update/insert.
        int added = 0, updated = 0, unchanged = 0;
        for (NewItem r : rows) {
            Optional<CodeItemDto> existing = relationalStore.findDraftItemByKey(versionId, r.keyParts());
            if (existing.isPresent()) {
                CodeItemDto cur = existing.get();
                int n = relationalStore.updateDraftItemById(
                        versionId,
                        UUID.fromString(cur.id()),
                        cur.rowVersion() == null ? 0 : cur.rowVersion(),
                        r.attributes() != null ? r.attributes() : cur.attributes(),
                        r.parentKey() != null ? r.parentKey() : cur.parentKey(),
                        r.parentRef() != null ? r.parentRef() : cur.parentRef(),
                        r.labelRu() != null ? r.labelRu() : cur.labelRu(),
                        r.labelEn() != null ? r.labelEn() : cur.labelEn(),
                        r.descriptionRu() != null ? r.descriptionRu() : cur.descriptionRu(),
                        r.descriptionEn() != null ? r.descriptionEn() : cur.descriptionEn(),
                        r.orderIndex() != null ? r.orderIndex() : cur.orderIndex(),
                        r.status() != null ? r.status() : cur.status(),
                        r.effectiveFrom() != null ? dateText(r.effectiveFrom()) : cur.effectiveFrom(),
                        r.effectiveTo() != null ? dateText(r.effectiveTo()) : cur.effectiveTo());
                if (n == 1) updated++;
                else unchanged++;
            } else {
                relationalStore.insertDraftItem(
                        versionId,
                        r.keyParts(),
                        r.attributes() == null ? Map.of() : r.attributes(),
                        r.parentKey(),
                        r.parentRef(),
                        r.labelRu(),
                        r.labelEn(),
                        r.descriptionRu(),
                        r.descriptionEn(),
                        r.orderIndex(),
                        r.status(),
                        dateText(r.effectiveFrom()),
                        dateText(r.effectiveTo()));
                added++;
            }
        }
        refreshItemCount(versionId);
        log.info("authoring: bulk upsert version_id={} added={} updated={} unchanged={} by={}",
                versionId, added, updated, unchanged, author);
        return BulkResult.applied(rows.size(), added, updated, unchanged);
    }

    public BulkResult bulkUpsertCsv(UUID versionId, InputStream csvIn, UUID author) {
        List<CsvBulkParser.Row> raw;
        try {
            raw = csv.parse(csvIn);
        } catch (IOException | RuntimeException e) {
            // RuntimeException ловит IllegalArgumentException из самого parser'а и
            // RuntimeJsonMappingException из jackson-csv MappingIterator на сломанной строке.
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return BulkResult.rejected(0, List.of(new BulkError(-1, null, "csv", msg)));
        }
        List<NewItem> mapped = new ArrayList<>(raw.size());
        for (CsvBulkParser.Row r : raw) {
            mapped.add(new NewItem(
                    r.keyParts(),
                    r.parentKey(),
                    null /* parent_ref не задаётся через CSV в MVP */,
                    r.labelRu(),
                    r.labelEn(),
                    r.descriptionRu(),
                    r.descriptionEn(),
                    r.attributes(),
                    r.orderIndex(),
                    r.status(),
                    r.effectiveFrom(),
                    r.effectiveTo()));
        }
        return bulkUpsertJson(versionId, mapped, author);
    }

    /**
     * Bulk-import из XLSX (новая фича: импорт справочников из Excel). Контракт колонок
     * идентичен {@link #bulkUpsertCsv} — парсер делегирует в тот же row-builder. Вся
     * пачка атомарна: любая ошибка парсинга → REJECTED, ничего не записано.
     */
    public BulkResult bulkUpsertXlsx(UUID versionId, InputStream xlsxIn, UUID author) {
        List<CsvBulkParser.Row> raw;
        try {
            raw = xlsx.parse(xlsxIn);
        } catch (IOException | RuntimeException e) {
            // RuntimeException ловит IllegalArgumentException парсера и любые
            // ошибки fastexcel-reader на битой/не-XLSX книге.
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return BulkResult.rejected(0, List.of(new BulkError(-1, null, "xlsx", msg)));
        }
        return upsertParsedRows(versionId, raw, author);
    }

    /**
     * Bulk-import pivot-XLSX матриц миграций (E19). Раскладывает квадратную матрицу
     * в triples {@code (from, to, horizon, probability)}. Если {@code IMPLICIT_DEFAULT}
     * и сумма строки &lt; 1 — дописывает absorbing-колонку из невязки. Возвращает
     * стандартный {@link BulkResult} с дополнительным числом дописанных ячеек
     * в {@code implicitDefaultAdded} (через side-channel field, см. ниже).
     */
    public BulkResult bulkUpsertXlsxPivot(
            UUID versionId,
            InputStream xlsxIn,
            String horizon,
            MatrixPivotSheetParser.RowResidualPolicy policy,
            UUID author) {
        MatrixPivotSheetParser parser = new MatrixPivotSheetParser(json, horizon, policy);
        MatrixPivotSheetParser.PivotParseResult parsed;
        try {
            parsed = parser.parse(xlsxIn);
        } catch (IOException | RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return BulkResult.rejected(0, List.of(new BulkError(-1, null, "xlsx-pivot", msg)));
        }
        BulkResult base = upsertParsedRows(versionId, parsed.rows(), author);
        return base.withImplicitDefaultAdded(parsed.implicitDefaultAdded());
    }

    /** Общий путь long-формата и pivot после разбора в triples — DRY с CSV. */
    private BulkResult upsertParsedRows(UUID versionId, List<CsvBulkParser.Row> raw, UUID author) {
        List<NewItem> mapped = new ArrayList<>(raw.size());
        for (CsvBulkParser.Row r : raw) {
            mapped.add(new NewItem(
                    r.keyParts(),
                    r.parentKey(),
                    null /* parent_ref не задаётся через XLSX в MVP */,
                    r.labelRu(),
                    r.labelEn(),
                    r.descriptionRu(),
                    r.descriptionEn(),
                    r.attributes(),
                    r.orderIndex(),
                    r.status(),
                    r.effectiveFrom(),
                    r.effectiveTo()));
        }
        return bulkUpsertJson(versionId, mapped, author);
    }

    // ── Diff ────────────────────────────────────────────────────────────────────

    public DiffCalculator.Result diff(UUID toVersionId, UUID fromVersionId) {
        VersionRow to = jdbi.withExtension(CodeSetVersionDao.class, dao -> dao.findById(toVersionId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown to-version: " + toVersionId));
        VersionRow from = jdbi.withExtension(CodeSetVersionDao.class, dao -> dao.findById(fromVersionId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown from-version: " + fromVersionId));
        if (!to.codesetId().equals(from.codesetId())) {
            throw new IllegalArgumentException(
                    "Cannot diff across codesets: " + to.codesetId() + " vs " + from.codesetId());
        }
        // Stage 7c: колоночный diff по rd_data (__draft/__history), маппим в Result.
        RelationalStoreService.RelDiffSummary rel = relationalStore.diff(fromVersionId, toVersionId);
        List<DiffCalculator.Entry> entries = new ArrayList<>();
        for (RelationalStoreService.RelDiffEntry e : rel.entries()) {
            entries.add(new DiffCalculator.Entry(e.op(), e.keyParts(), e.changedColumns(), null, null));
        }
        return new DiffCalculator.Result(from.version(), to.version(), entries,
                new DiffCalculator.Summary(rel.added(), rel.changed(), rel.removed(), 0));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private VersionContext loadDraftContext(UUID versionId) {
        VersionRow row = jdbi.withExtension(CodeSetVersionDao.class, dao -> dao.findById(versionId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));
        if (!"DRAFT".equals(row.status())) {
            throw new IllegalStateException(
                    "Version " + versionId + " is " + row.status() + ", only DRAFT is editable");
        }
        CodeSetSchemaSnapshot schema = catalog.schemaByVersion(row.codesetId(), row.schemaVersion())
                .orElseThrow(() -> new IllegalStateException("Missing CodeSetSchema codeset_id=" + row.codesetId()
                        + " schema_version=" + row.schemaVersion()));
        return new VersionContext(row.codesetId(), row.schemaVersion(), schema.jsonSchemaText());
    }

    /** LocalDate → ISO-строка для relational-стора (он биндит даты текстом с CAST). */
    private static String dateText(LocalDate d) {
        return d == null ? null : d.toString();
    }

    /** Пересчитывает item_count версии из rd_data (__draft) и пишет в code_set_version. */
    private void refreshItemCount(UUID versionId) {
        int count = relationalStore.countDraftItems(versionId);
        jdbi.useExtension(CodeSetVersionDao.class, dao -> dao.setItemCount(versionId, count));
    }

    private void validateOrThrow(VersionContext ctx, Map<String, Object> attributes, String forKey) {
        if (attributes == null) return;
        List<String> errs = validator.validate(ctx.codesetId(), ctx.schemaVersion(), ctx.schemaText(), attributes);
        if (!errs.isEmpty()) {
            throw new ValidationException(
                    "attributes do not match CodeSetSchema (" + forKey + "): " + String.join("; ", errs));
        }
    }

    private static String keyDescription(List<String> key) {
        return key == null ? "<no-key>" : String.join("|", key);
    }

    /**
     * Stage 7 — жёсткая ссылочная целостность: значение FK-колонки должно
     * существовать в опубликованном родителе. Бросает {@link ValidationException}
     * (REST → 400) с понятным перечнем нарушений.
     */
    private void assertReferencesOrThrow(
            UUID codesetId, List<String> keyParts, Map<String, Object> attributes) {
        List<String> violations = relationalStore.referenceViolations(codesetId, keyParts, attributes);
        if (!violations.isEmpty()) {
            throw new ValidationException(
                    "нарушена ссылочная целостность: " + String.join("; ", violations));
        }
    }

    // ── DTO ─────────────────────────────────────────────────────────────────────

    public record NewItem(
            List<String> keyParts,
            List<String> parentKey,
            Map<String, Object> parentRef,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Map<String, Object> attributes,
            Integer orderIndex,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    public record ItemPatch(
            int expectedRowVersion,
            List<String> parentKey,
            Map<String, Object> parentRef,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Map<String, Object> attributes,
            Integer orderIndex,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    public record ItemsPage(int page, int size, int total, List<CodeItemDto> items) {}

    public record BulkResult(
            String status,
            int rowsTotal,
            int rowsAdded,
            int rowsUpdated,
            int rowsUnchanged,
            List<BulkError> errors,
            int implicitDefaultAdded) {
        public static BulkResult applied(int total, int added, int updated, int unchanged) {
            return new BulkResult("APPLIED", total, added, updated, unchanged, List.of(), 0);
        }

        public static BulkResult rejected(int total, List<BulkError> errors) {
            return new BulkResult(
                    "REJECTED", total, 0, 0, 0, Collections.unmodifiableList(errors), 0);
        }

        /**
         * Возвращает новый BulkResult с обновлённым счётчиком дописанных residual-ячеек
         * (E19 pivot-import: implicit_default). 0 для long-формата и non-pivot путей.
         */
        public BulkResult withImplicitDefaultAdded(int n) {
            return new BulkResult(status, rowsTotal, rowsAdded, rowsUpdated, rowsUnchanged, errors, n);
        }
    }

    public record BulkError(int rowIndex, List<String> keyParts, String field, String message) {}

    private record VersionContext(UUID codesetId, int schemaVersion, String schemaText) {}

    /** Конфликт optimistic-lock'а — service бросает, resource ловит и отдаёт 409. */
    public static final class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }

    /** Не прошла валидация attributes — resource отдаёт 422. */
    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

}
