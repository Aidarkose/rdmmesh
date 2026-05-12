package bank.rdmmesh.authoring.internal.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.CatalogReadPort.CodeSetSchemaSnapshot;
import bank.rdmmesh.api.port.CatalogReadPort.CodeSetSnapshot;
import bank.rdmmesh.authoring.internal.AuthoringMappers;
import bank.rdmmesh.authoring.internal.SemVer;
import bank.rdmmesh.authoring.internal.csv.CsvBulkParser;
import bank.rdmmesh.authoring.internal.dao.CodeItemClosureDao;
import bank.rdmmesh.authoring.internal.dao.CodeItemDao;
import bank.rdmmesh.authoring.internal.dao.CodeItemDao.ItemRow;
import bank.rdmmesh.authoring.internal.dao.CodeItemDiffDao;
import bank.rdmmesh.authoring.internal.dao.CodeSetVersionDao;
import bank.rdmmesh.authoring.internal.dao.CodeSetVersionDao.VersionRow;
import bank.rdmmesh.authoring.internal.diff.DiffCalculator;
import bank.rdmmesh.authoring.internal.validation.AttributesValidator;
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
    private final DiffCalculator differ;
    private final CsvBulkParser csv;

    public AuthoringService(Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json) {
        this.jdbi = jdbi;
        this.catalog = catalog;
        this.json = json;
        this.validator = new AttributesValidator(json);
        this.differ = new DiffCalculator(json);
        this.csv = new CsvBulkParser(json);
    }

    // ── Versions ────────────────────────────────────────────────────────────────

    public List<CodeSetVersion> listVersions(UUID codesetId) {
        return jdbi.withExtension(CodeSetVersionDao.class, dao -> dao.findByCodeset(codesetId)
                .stream()
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
            UUID codesetId,
            String requestedVersion,
            String bump,
            String releaseChannel,
            UUID createdBy) {

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
                throw new IllegalArgumentException(
                        "CodeSet " + codesetId + " уже имеет открытую версию "
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
                throw new IllegalArgumentException(
                        "Version " + version + " already exists for codeset " + codesetId);
            }

            UUID newId = UUID.randomUUID();
            int n = versionDao.insertDraft(
                    newId,
                    codesetId,
                    version,
                    codeSet.schemaVersion(),
                    releaseChannel,
                    createdBy);
            if (n != 1) throw new IllegalStateException("INSERT code_set_version returned " + n);

            int copied = base.map(b -> cloneItems(handle, b.id(), newId)).orElse(0);
            // Closure обслуживается AFTER-INSERT триггером (V022). Каждый INSERT в
            // code_item уже подцепил свою цепочку; rebuild не нужен.
            versionDao.setItemCount(newId, copied);

            log.info("authoring: создан draft codeset_id={} version={} from={} items={}",
                    codesetId, version,
                    base.map(VersionRow::version).orElse("<empty>"), copied);
            return AuthoringMappers.toVersion(versionDao.findById(newId).orElseThrow());
        });
    }

    public boolean deleteDraft(UUID versionId) {
        return jdbi.inTransaction(handle -> {
            // closure уйдёт через ON DELETE CASCADE (внешний ключ) с CodeSetVersion.
            int n = handle.attach(CodeSetVersionDao.class).deleteDraft(versionId);
            return n == 1;
        });
    }

    /**
     * Disaster-recovery: пересобрать closure-table для указанной версии. В обычной
     * работе обслуживание идёт триггерами (V022/V023); вызов нужен после ручных
     * SQL-вмешательств либо когда V023 sanity check выдал WARN на старте.
     *
     * <p>В одной транзакции: {@code DELETE all closure-rows for versionId} +
     * {@code WITH RECURSIVE} rebuild через actual {@code code_item}. Триггеры на
     * {@code code_item} не дёргаются (мы не трогаем эту таблицу).
     *
     * @throws IllegalArgumentException если версии нет
     */
    public ClosureRebuildResult rebuildClosure(UUID versionId, UUID admin) {
        return jdbi.inTransaction(handle -> {
            VersionRow version = handle.attach(CodeSetVersionDao.class).findById(versionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Version not found: " + versionId));
            CodeItemClosureDao dao = handle.attach(CodeItemClosureDao.class);
            int removed = dao.deleteAllForVersion(versionId);
            int inserted = dao.rebuild(versionId);
            int total = dao.countForVersion(versionId);
            log.warn("authoring: closure rebuild version_id={} (status={}) removed={} inserted={} total={} by_admin={}",
                    versionId, version.status(), removed, inserted, total, admin);
            return new ClosureRebuildResult(versionId, removed, inserted, total);
        });
    }

    // ── Items ───────────────────────────────────────────────────────────────────

    public ItemsPage listItems(UUID versionId, int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 100;
        if (size > 10_000) size = 10_000;
        int offset = page * size;
        int finalSize = size;
        int finalPage = page;
        return jdbi.withHandle(h -> {
            CodeItemDao dao = h.attach(CodeItemDao.class);
            int total = dao.countByVersion(versionId);
            List<CodeItemDto> items = dao.page(versionId, offset, finalSize).stream()
                    .map(AuthoringMappers::toItem)
                    .toList();
            return new ItemsPage(finalPage, finalSize, total, items);
        });
    }

    public Optional<CodeItemDto> findItemByKey(UUID versionId, List<String> keyParts) {
        return jdbi.withExtension(CodeItemDao.class, dao -> dao.findByKey(versionId, jsonOf(keyParts)))
                .map(AuthoringMappers::toItem);
    }

    public CodeItemDto addItem(UUID versionId, NewItem req, UUID author) {
        VersionContext ctx = loadDraftContext(versionId);
        validateOrThrow(ctx, req.attributes(), keyDescription(req.keyParts()));

        return jdbi.inTransaction(handle -> {
            CodeItemDao dao = handle.attach(CodeItemDao.class);
            UUID id = UUID.randomUUID();
            try {
                int n = dao.insert(
                        id,
                        versionId,
                        jsonOf(req.keyParts()),
                        jsonOfNullable(req.parentKey()),
                        jsonOfNullable(req.parentRef()),
                        req.labelRu(),
                        req.labelEn(),
                        req.descriptionRu(),
                        req.descriptionEn(),
                        jsonOfNullable(req.attributes() == null ? Map.of() : req.attributes()),
                        req.orderIndex(),
                        req.status(),
                        req.effectiveFrom(),
                        req.effectiveTo());
                if (n != 1) throw new IllegalStateException("INSERT code_item returned " + n);
            } catch (UnableToExecuteStatementException e) {
                if (isUniqueViolation(e)) {
                    throw new IllegalArgumentException(
                            "Item with key " + req.keyParts() + " already exists in this version");
                }
                throw e;
            }
            // Closure обновляется AFTER-INSERT триггером (V022).
            int count = handle.attach(CodeItemDao.class).countByVersion(versionId);
            handle.attach(CodeSetVersionDao.class).setItemCount(versionId, count);
            log.debug("authoring: + item version_id={} key={} by={}", versionId, req.keyParts(), author);
            return AuthoringMappers.toItem(dao.findById(id).orElseThrow());
        });
    }

    public CodeItemDto updateItem(UUID versionId, UUID itemId, ItemPatch patch, UUID author) {
        VersionContext ctx = loadDraftContext(versionId);
        if (patch.attributes() != null) {
            validateOrThrow(ctx, patch.attributes(), "id=" + itemId);
        }

        return jdbi.inTransaction(handle -> {
            CodeItemDao dao = handle.attach(CodeItemDao.class);
            ItemRow current = dao.findById(itemId)
                    .filter(r -> r.versionId().equals(versionId))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Item not found: " + itemId + " in version " + versionId));

            int n = dao.updateInDraft(
                    itemId,
                    patch.expectedRowVersion(),
                    patch.parentKey() == null
                            ? current.parentKeyJson()
                            : jsonOfNullable(patch.parentKey()),
                    patch.parentRef() == null
                            ? current.parentRefJson()
                            : jsonOfNullable(patch.parentRef()),
                    patch.labelRu()        != null ? patch.labelRu()        : current.labelRu(),
                    patch.labelEn()        != null ? patch.labelEn()        : current.labelEn(),
                    patch.descriptionRu()  != null ? patch.descriptionRu()  : current.descriptionRu(),
                    patch.descriptionEn()  != null ? patch.descriptionEn()  : current.descriptionEn(),
                    patch.attributes() == null
                            ? current.attributesJson()
                            : jsonOfNullable(patch.attributes()),
                    patch.orderIndex(),
                    patch.status(),
                    patch.effectiveFrom() != null ? patch.effectiveFrom() : current.effectiveFrom(),
                    patch.effectiveTo()   != null ? patch.effectiveTo()   : current.effectiveTo());
            if (n == 0) {
                throw new OptimisticLockException(
                        "Stale row_version for item " + itemId + ": expected "
                                + patch.expectedRowVersion() + " (current " + current.rowVersion() + ")");
            }
            // Closure обновляется AFTER-UPDATE-OF-parent_key триггером (V022).
            // На UPDATE без изменения parent_key триггер — no-op (см. V022).
            log.debug("authoring: ~ item version_id={} id={} by={}", versionId, itemId, author);
            return AuthoringMappers.toItem(dao.findById(itemId).orElseThrow());
        });
    }

    public boolean deleteItem(UUID versionId, UUID itemId, UUID author) {
        loadDraftContext(versionId); // только чтобы проверить, что это DRAFT
        return jdbi.inTransaction(handle -> {
            CodeItemDao dao = handle.attach(CodeItemDao.class);
            int n = dao.deleteInDraft(itemId);
            if (n == 0) return false;
            // Closure обновляется AFTER-DELETE триггером (V022).
            int count = dao.countByVersion(versionId);
            handle.attach(CodeSetVersionDao.class).setItemCount(versionId, count);
            log.debug("authoring: - item version_id={} id={} by={}", versionId, itemId, author);
            return true;
        });
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
            List<String> errs = validator.validate(
                    ctx.codesetId(), ctx.schemaVersion(), ctx.schemaText(), r.attributes());
            for (String e : errs) errors.add(new BulkError(i, r.keyParts(), "attributes", e));
        }
        if (!errors.isEmpty()) {
            return BulkResult.rejected(rows.size(), errors);
        }
        return jdbi.inTransaction(handle -> {
            int added = 0, updated = 0, unchanged = 0;
            CodeItemDao dao = handle.attach(CodeItemDao.class);
            for (NewItem r : rows) {
                Optional<ItemRow> existing = dao.findByKey(versionId, jsonOf(r.keyParts()));
                if (existing.isPresent()) {
                    ItemRow cur = existing.get();
                    int n = dao.updateInDraft(
                            cur.id(),
                            cur.rowVersion(),
                            r.parentKey() == null ? cur.parentKeyJson() : jsonOfNullable(r.parentKey()),
                            r.parentRef() == null ? cur.parentRefJson() : jsonOfNullable(r.parentRef()),
                            r.labelRu()        != null ? r.labelRu()        : cur.labelRu(),
                            r.labelEn()        != null ? r.labelEn()        : cur.labelEn(),
                            r.descriptionRu()  != null ? r.descriptionRu()  : cur.descriptionRu(),
                            r.descriptionEn()  != null ? r.descriptionEn()  : cur.descriptionEn(),
                            r.attributes() == null ? cur.attributesJson() : jsonOfNullable(r.attributes()),
                            r.orderIndex(),
                            r.status(),
                            r.effectiveFrom() != null ? r.effectiveFrom() : cur.effectiveFrom(),
                            r.effectiveTo()   != null ? r.effectiveTo()   : cur.effectiveTo());
                    if (n == 1) updated++; else unchanged++;
                } else {
                    dao.insert(
                            UUID.randomUUID(),
                            versionId,
                            jsonOf(r.keyParts()),
                            jsonOfNullable(r.parentKey()),
                            jsonOfNullable(r.parentRef()),
                            r.labelRu(),
                            r.labelEn(),
                            r.descriptionRu(),
                            r.descriptionEn(),
                            jsonOfNullable(r.attributes() == null ? Map.of() : r.attributes()),
                            r.orderIndex(),
                            r.status(),
                            r.effectiveFrom(),
                            r.effectiveTo());
                    added++;
                }
            }
            // Closure обновляется построчно AFTER-триггерами (V022).
            int count = dao.countByVersion(versionId);
            handle.attach(CodeSetVersionDao.class).setItemCount(versionId, count);
            log.info("authoring: bulk upsert version_id={} added={} updated={} unchanged={} by={}",
                    versionId, added, updated, unchanged, author);
            return BulkResult.applied(rows.size(), added, updated, unchanged);
        });
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

    // ── Diff ────────────────────────────────────────────────────────────────────

    public DiffCalculator.Result diff(UUID toVersionId, UUID fromVersionId) {
        return jdbi.withHandle(handle -> {
            CodeSetVersionDao versionDao = handle.attach(CodeSetVersionDao.class);
            VersionRow to = versionDao.findById(toVersionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown to-version: " + toVersionId));
            VersionRow from = versionDao.findById(fromVersionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown from-version: " + fromVersionId));
            if (!to.codesetId().equals(from.codesetId())) {
                throw new IllegalArgumentException(
                        "Cannot diff across codesets: " + to.codesetId() + " vs " + from.codesetId());
            }
            var rows = handle.attach(CodeItemDiffDao.class).diff(fromVersionId, toVersionId);
            return differ.compute(from.version(), to.version(), rows);
        });
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
                .orElseThrow(() -> new IllegalStateException(
                        "Missing CodeSetSchema codeset_id=" + row.codesetId()
                                + " schema_version=" + row.schemaVersion()));
        return new VersionContext(row.codesetId(), row.schemaVersion(), schema.jsonSchemaText());
    }

    private void validateOrThrow(VersionContext ctx, Map<String, Object> attributes, String forKey) {
        if (attributes == null) return;
        List<String> errs = validator.validate(
                ctx.codesetId(), ctx.schemaVersion(), ctx.schemaText(), attributes);
        if (!errs.isEmpty()) {
            throw new ValidationException(
                    "attributes do not match CodeSetSchema (" + forKey + "): " + String.join("; ", errs));
        }
    }

    private int cloneItems(Handle handle, UUID fromVersionId, UUID toVersionId) {
        // Atomic SELECT-INSERT: новые id, new system_from, row_version=0; всё остальное — копия.
        return handle.createUpdate(
                """
                INSERT INTO authoring.code_item
                    (id, version_id, key_parts, parent_key, parent_ref,
                     label_ru, label_en, description_ru, description_en,
                     attributes, order_index, status, effective_from, effective_to,
                     row_version)
                SELECT gen_random_uuid(), :toVersion, key_parts, parent_key, parent_ref,
                       label_ru, label_en, description_ru, description_en,
                       attributes, order_index, status, effective_from, effective_to,
                       0
                  FROM authoring.code_item
                 WHERE version_id = :fromVersion
                """)
                .bind("toVersion", toVersionId)
                .bind("fromVersion", fromVersionId)
                .execute();
    }

    private static String jsonOf(List<String> list) {
        return AuthoringMappers.writeJson(list);
    }

    private static String jsonOfNullable(Object value) {
        if (value == null) return null;
        return AuthoringMappers.writeJson(value);
    }

    private static String keyDescription(List<String> key) {
        return key == null ? "<no-key>" : String.join("|", key);
    }

    private static boolean isUniqueViolation(UnableToExecuteStatementException e) {
        if (e.getCause() instanceof java.sql.SQLException sql) {
            // Postgres SQLState для unique_violation.
            return "23505".equals(sql.getSQLState());
        }
        return false;
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
            String status, int rowsTotal, int rowsAdded, int rowsUpdated,
            int rowsUnchanged, List<BulkError> errors) {
        public static BulkResult applied(int total, int added, int updated, int unchanged) {
            return new BulkResult("APPLIED", total, added, updated, unchanged, List.of());
        }
        public static BulkResult rejected(int total, List<BulkError> errors) {
            return new BulkResult("REJECTED", total, 0, 0, 0, Collections.unmodifiableList(errors));
        }
    }

    public record BulkError(int rowIndex, List<String> keyParts, String field, String message) {}

    /** Результат disaster-recovery closure rebuild'а. */
    public record ClosureRebuildResult(UUID versionId, int removed, int inserted, int total) {}

    private record VersionContext(UUID codesetId, int schemaVersion, String schemaText) {}

    /** Конфликт optimistic-lock'а — service бросает, resource ловит и отдаёт 409. */
    public static final class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) { super(message); }
    }

    /** Не прошла валидация attributes — resource отдаёт 422. */
    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }

    private Map<String, Object> safeAttributes(Map<String, Object> attrs) {
        return attrs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attrs);
    }
}
