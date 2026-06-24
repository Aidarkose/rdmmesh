package bank.rdmmesh.catalog.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.catalog.internal.dao.CodeSetDao;
import bank.rdmmesh.catalog.internal.dao.CodeSetSchemaDao;
import bank.rdmmesh.catalog.internal.dao.DomainDao;

/**
 * Реализация {@link CatalogReadPort} над тем же {@link Jdbi}, что и {@code CatalogService}.
 * Не идёт через сервис, чтобы не тащить в порт мутации/проверки доменной логики — здесь
 * только чтение из {@code catalog.code_set} / {@code catalog.code_set_schema}.
 */
public final class CatalogReadAdapter implements CatalogReadPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Jdbi jdbi;

    public CatalogReadAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Optional<CodeSetSnapshot> findCodeSet(UUID codesetId) {
        return jdbi.withExtension(CodeSetDao.class, dao -> dao.findById(codesetId))
                .map(row -> new CodeSetSnapshot(
                        row.id(),
                        row.domainId(),
                        row.name(),
                        row.hierarchyMode(),
                        row.schemaVersion() == null ? 1 : row.schemaVersion(),
                        row.currentPublishedVersion(),
                        row.keySpecJson(),
                        row.deletedAt() != null));
    }

    @Override
    public Optional<CodeSetSchemaSnapshot> currentSchema(UUID codesetId) {
        return jdbi.withHandle(handle -> {
            var codeSet = handle.attach(CodeSetDao.class).findById(codesetId);
            if (codeSet.isEmpty()) return Optional.<CodeSetSchemaSnapshot>empty();
            int active = codeSet.get().schemaVersion() == null ? 1 : codeSet.get().schemaVersion();
            return handle.attach(CodeSetSchemaDao.class)
                    .findByCodesetAndVersion(codesetId, active)
                    .map(s -> new CodeSetSchemaSnapshot(s.codesetId(), s.version(), s.jsonSchemaText()));
        });
    }

    @Override
    public Optional<CodeSetSchemaSnapshot> schemaByVersion(UUID codesetId, int schemaVersion) {
        return jdbi.withExtension(CodeSetSchemaDao.class,
                        dao -> dao.findByCodesetAndVersion(codesetId, schemaVersion))
                .map(s -> new CodeSetSchemaSnapshot(s.codesetId(), s.version(), s.jsonSchemaText()));
    }

    @Override
    public Optional<DomainSnapshot> findDomain(UUID domainId) {
        return jdbi.withExtension(DomainDao.class, dao -> dao.findById(domainId))
                .map(row -> new DomainSnapshot(row.id(), row.name(), row.displayName()));
    }

    @Override
    public List<CodeSetReferenceSnapshot> referencesOf(UUID codesetId) {
        String json = jdbi.withExtension(CodeSetDao.class, dao -> dao.findById(codesetId))
                .map(CodeSetDao.CodeSetRow::columnRefsJson)
                .orElse(null);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = JSON.readTree(json);
            List<CodeSetReferenceSnapshot> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode ref : arr) {
                    String from = ref.path("from_column").asText(null);
                    String toCodeset = ref.path("to_codeset_id").asText(null);
                    String to = ref.path("to_column").asText(null);
                    if (from == null || toCodeset == null || to == null) {
                        continue;
                    }
                    out.add(new CodeSetReferenceSnapshot(from, UUID.fromString(toCodeset), to));
                }
            }
            return out;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // column_refs descriptive (E25) — битый JSON не должен ронять читателя.
            return List.of();
        }
    }
}
