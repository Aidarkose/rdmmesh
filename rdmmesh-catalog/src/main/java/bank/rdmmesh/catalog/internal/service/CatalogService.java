package bank.rdmmesh.catalog.internal.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.catalog.internal.dao.CodeSetDao;
import bank.rdmmesh.catalog.internal.dao.CodeSetSchemaDao;
import bank.rdmmesh.catalog.internal.dao.DomainDao;
import bank.rdmmesh.catalog.internal.mapper.CatalogMappers;
import bank.rdmmesh.catalog.resource.CodeSetSchemaDto;
import bank.rdmmesh.spec.entity.CodeSet;
import bank.rdmmesh.spec.entity.Domain;

/**
 * Бизнес-операции catalog'а — единственное место, которое пишет в schema {@code catalog}.
 * Resource'ы только адаптируют HTTP ↔ Java, всю логику зовут здесь.
 *
 * <p>Транзакции делаются через {@link Jdbi#useTransaction(...)} / {@link Jdbi#inTransaction(...)};
 * provisional-owner вставка идёт в той же транзакции, что и INSERT CodeSet'а — иначе мы
 * рискуем оставить «осиротевший» CodeSet без owner'а если процесс упадёт между двумя
 * вызовами.
 */
public final class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final Jdbi jdbi;
    private final OwnershipPort ownership;

    public CatalogService(Jdbi jdbi, OwnershipPort ownership) {
        this.jdbi = jdbi;
        this.ownership = ownership;
    }

    // ── Domain ──────────────────────────────────────────────────────────────────

    public List<Domain> listDomains() {
        return jdbi.withExtension(DomainDao.class, dao -> dao.findAll().stream()
                .map(CatalogMappers::toDomain)
                .toList());
    }

    public Optional<Domain> findDomain(UUID id) {
        return jdbi.withExtension(DomainDao.class, dao -> dao.findById(id))
                .map(CatalogMappers::toDomain);
    }

    /**
     * Создаёт локальную «зеркальную» запись domain'а. В нормальной операции вызывается из
     * OM ingestion path (E12); здесь — для bootstrap'а dev-стенда и пилотных доменов.
     * Идемпотентно по {@code (om_domain_id, name)} — повторный вызов с теми же ID/name
     * вернёт существующую запись.
     */
    public Domain createDomain(NewDomain req) {
        return jdbi.inTransaction(handle -> {
            DomainDao dao = handle.attach(DomainDao.class);
            // Идемпотентность: если уже есть row с тем же om_domain_id — возвращаем её.
            var existing = dao.findByOmId(req.omDomainId());
            if (existing.isPresent()) {
                return CatalogMappers.toDomain(existing.get());
            }
            UUID id = UUID.randomUUID();
            int n = dao.insert(
                    id,
                    req.omDomainId(),
                    req.name(),
                    req.displayName(),
                    req.description(),
                    req.labelRu(),
                    req.labelEn(),
                    req.tags() == null ? new String[0] : req.tags());
            if (n != 1) {
                throw new IllegalStateException("INSERT catalog.domain returned " + n);
            }
            log.info("catalog: создан domain id={} om_domain_id={} name={}",
                    id, req.omDomainId(), req.name());
            return CatalogMappers.toDomain(dao.findById(id).orElseThrow());
        });
    }

    public Optional<Domain> patchDomain(UUID id, DomainPatch patch) {
        return jdbi.inTransaction(handle -> {
            DomainDao dao = handle.attach(DomainDao.class);
            int n = dao.patch(
                    id,
                    patch.displayName(),
                    patch.description(),
                    patch.labelRu(),
                    patch.labelEn(),
                    patch.tags());
            if (n == 0) return Optional.<Domain>empty();
            return dao.findById(id).map(CatalogMappers::toDomain);
        });
    }

    // ── CodeSet ─────────────────────────────────────────────────────────────────

    public List<CodeSet> listCodeSets(UUID domainId) {
        return jdbi.withExtension(CodeSetDao.class, dao -> dao.findActiveByDomain(domainId)
                .stream()
                .map(CatalogMappers::toCodeSet)
                .toList());
    }

    public Optional<CodeSet> findCodeSet(UUID id) {
        return jdbi.withExtension(CodeSetDao.class, dao -> dao.findById(id))
                .map(CatalogMappers::toCodeSet);
    }

    /**
     * Создаёт CodeSet вместе с initial CodeSetSchema (version=1) и provisional owner'ом.
     * Всё в одной транзакции — три INSERT'а через JDBI handle.attach(...).
     */
    public CodeSet createCodeSet(NewCodeSet req, UUID createdBy) {
        return jdbi.inTransaction(handle -> {
            CodeSetDao codeSetDao = handle.attach(CodeSetDao.class);

            // Гарантируем, что domain существует.
            DomainDao domainDao = handle.attach(DomainDao.class);
            domainDao.findById(req.domainId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown domain_id: " + req.domainId()));

            // Защита от дубликата.
            if (codeSetDao.findByDomainAndName(req.domainId(), req.name()).isPresent()) {
                throw new IllegalArgumentException(
                        "CodeSet '" + req.name() + "' already exists in domain " + req.domainId());
            }

            UUID codeSetId = UUID.randomUUID();
            int inserted = codeSetDao.insert(
                    codeSetId,
                    req.domainId(),
                    req.name(),
                    req.displayName(),
                    req.description(),
                    req.labelRu(),
                    req.labelEn(),
                    req.tags() == null ? new String[0] : req.tags(),
                    req.keySpecJson(),
                    req.hierarchyMode(),
                    req.releaseChannels() == null
                            ? new String[] {"PROD"}
                            : req.releaseChannels(),
                    createdBy);
            if (inserted != 1) {
                throw new IllegalStateException("INSERT catalog.code_set returned " + inserted);
            }

            // Initial schema v1 (минимальный — пустой объект, расширяется через PUT
            // /codesets/{id}/schema позже Schema Designer'ом).
            CodeSetSchemaDao schemaDao = handle.attach(CodeSetSchemaDao.class);
            schemaDao.insert(
                    UUID.randomUUID(),
                    codeSetId,
                    1,
                    req.initialSchemaJson() == null ? "{}" : req.initialSchemaJson(),
                    createdBy);

            // Provisional owner — bootstrap до прихода реального owner'а из OM webhook'а.
            ownership.assignProvisionalOwner(codeSetId, "CODESET", createdBy);

            log.info("catalog: создан code_set id={} domain_id={} name={} created_by={}",
                    codeSetId, req.domainId(), req.name(), createdBy);
            return CatalogMappers.toCodeSet(codeSetDao.findById(codeSetId).orElseThrow());
        });
    }

    public Optional<CodeSet> patchCodeSetMetadata(UUID id, CodeSetPatch patch) {
        return jdbi.inTransaction(handle -> {
            CodeSetDao dao = handle.attach(CodeSetDao.class);
            int n = dao.patchMetadata(
                    id,
                    patch.displayName(),
                    patch.description(),
                    patch.labelRu(),
                    patch.labelEn(),
                    patch.tags());
            if (n == 0) return Optional.<CodeSet>empty();
            return dao.findById(id).map(CatalogMappers::toCodeSet);
        });
    }

    /**
     * Заменяет набор cross-codeset FK-связей справочника целиком. {@code columnRefsJson} —
     * сырой JSON-массив (см. spec entity/code-set.json#/properties/references), который
     * service пишет в JSONB-поле {@code column_refs} как есть. Возвращает обновлённый CodeSet
     * или empty, если справочник не найден / soft-deleted.
     */
    public Optional<CodeSet> setReferences(UUID id, String columnRefsJson) {
        return jdbi.inTransaction(handle -> {
            CodeSetDao dao = handle.attach(CodeSetDao.class);
            int n = dao.updateReferences(id, columnRefsJson == null ? "[]" : columnRefsJson);
            if (n == 0) return Optional.<CodeSet>empty();
            return dao.findById(id).map(CatalogMappers::toCodeSet);
        });
    }

    // ── CodeSetSchema ───────────────────────────────────────────────────────────

    /** Возвращает текущую (active) версию схемы. */
    public Optional<CodeSetSchemaDto> currentSchema(UUID codesetId) {
        return jdbi.withHandle(handle -> {
            CodeSetDao codeSetDao = handle.attach(CodeSetDao.class);
            var codeSet = codeSetDao.findById(codesetId);
            if (codeSet.isEmpty()) return Optional.<CodeSetSchemaDto>empty();
            int activeVersion = codeSet.get().schemaVersion();
            return handle.attach(CodeSetSchemaDao.class)
                    .findByCodesetAndVersion(codesetId, activeVersion)
                    .map(CatalogMappers::toSchema);
        });
    }

    public List<CodeSetSchemaDto> schemaHistory(UUID codesetId) {
        return jdbi.withExtension(CodeSetSchemaDao.class, dao -> dao.findByCodeset(codesetId)
                .stream()
                .map(CatalogMappers::toSchema)
                .toList());
    }

    /**
     * Создаёт новую (следующую) ревизию схемы и переключает на неё активный pointer.
     * SPEC §3.5: "PUT /codesets/{id}/schema создаёт major-bump в новой версии". Здесь
     * мы выпускаем именно «следующую» версию — без явных мажор/минор-семантик.
     */
    public CodeSetSchemaDto putSchemaRevision(UUID codesetId, String jsonSchemaText, UUID createdBy) {
        return jdbi.inTransaction(handle -> {
            CodeSetDao codeSetDao = handle.attach(CodeSetDao.class);
            codeSetDao.findById(codesetId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown codeset_id: " + codesetId));

            CodeSetSchemaDao schemaDao = handle.attach(CodeSetSchemaDao.class);
            int next = schemaDao.maxVersion(codesetId) + 1;
            schemaDao.insert(UUID.randomUUID(), codesetId, next, jsonSchemaText, createdBy);
            codeSetDao.bumpSchemaVersion(codesetId, next);

            log.info("catalog: схема обновлена codeset_id={} version={} by={}",
                    codesetId, next, createdBy);
            return CatalogMappers.toSchema(
                    schemaDao.findByCodesetAndVersion(codesetId, next).orElseThrow());
        });
    }

    // ── DTO ─────────────────────────────────────────────────────────────────────

    public record NewDomain(
            UUID omDomainId,
            String name,
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags) {}

    public record DomainPatch(
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags) {}

    public record NewCodeSet(
            UUID domainId,
            String name,
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags,
            /** key_spec как сырая JSON-строка — service записывает её в JSONB-поле как есть. */
            String keySpecJson,
            String hierarchyMode,
            String[] releaseChannels,
            /** initial schema (default {}). */
            String initialSchemaJson) {}

    public record CodeSetPatch(
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags) {}
}
