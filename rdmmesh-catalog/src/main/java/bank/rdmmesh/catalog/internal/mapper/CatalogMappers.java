package bank.rdmmesh.catalog.internal.mapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import bank.rdmmesh.catalog.internal.dao.CodeSetDao.CodeSetRow;
import bank.rdmmesh.catalog.internal.dao.CodeSetSchemaDao.SchemaRow;
import bank.rdmmesh.catalog.internal.dao.DomainDao.DomainRow;
import bank.rdmmesh.catalog.resource.CodeSetSchemaDto;
import bank.rdmmesh.spec.entity.CodeSet;
import bank.rdmmesh.spec.entity.CodeSet.HierarchyMode;
import bank.rdmmesh.spec.entity.CodeSetRef;
import bank.rdmmesh.spec.entity.CodeSetVersion.ReleaseChannel;
import bank.rdmmesh.spec.entity.Domain;
import bank.rdmmesh.spec.entity.KeySpec;
import bank.rdmmesh.spec.entity.LocalizedLabel;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Чистые функции «row → API DTO». Сгенерированные jsonschema2pojo POJO живут на String'ах
 * для UUID/timestamps, поэтому здесь делается явная конверсия. Никакой DI — все методы
 * статические, side-effect free.
 *
 * <p>Один {@link ObjectMapper} нужен только для парсинга поля {@code key_spec} из JSONB-строки
 * в POJO {@link KeySpec}. Все остальные сериализации идут через стандартный jackson Dropwizard'а.
 */
public final class CatalogMappers {

    // FAIL_ON_UNKNOWN_PROPERTIES=false — forward-compat: новые опциональные поля
    // в schemas (например, E20 label_codeset_ref) не должны ломать чтение
    // существующих CodeSet'ов до перегенерации POJO. Codegen всё равно обновит
    // POJO на следующем mvn compile, но сценарий «backend поднят на старой
    // сборке, а UI уже пишет новые поля» теперь безопасен.
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    private CatalogMappers() {}

    public static Domain toDomain(DomainRow row) {
        Domain d = new Domain();
        d.setId(row.id().toString());
        // E18 (ADR-0011): RDM-локальные домены (master=RDM) не имеют om_domain_id —
        // он NULL до явной линковки с OM. Старый маппер звал .toString() безусловно
        // и падал NPE на таких строках, валя GET /domains для всех пользователей.
        d.setOmDomainId(row.omDomainId() == null ? null : row.omDomainId().toString());
        d.setName(row.name());
        d.setDisplayName(row.displayName());
        d.setDescription(row.description());
        d.setLabels(toLabels(row.labelRu(), row.labelEn()));
        d.setTags(toList(row.tags()));
        d.setCreatedAt(toIsoString(row.createdAt()));
        d.setUpdatedAt(toIsoString(row.updatedAt()));
        return d;
    }

    public static CodeSet toCodeSet(CodeSetRow row) {
        CodeSet cs = new CodeSet();
        cs.setId(row.id().toString());
        cs.setDomainId(row.domainId().toString());
        cs.setName(row.name());
        cs.setDisplayName(row.displayName());
        cs.setDescription(row.description());
        cs.setLabels(toLabels(row.labelRu(), row.labelEn()));
        cs.setTags(toList(row.tags()));
        cs.setKeySpec(parseKeySpec(row.keySpecJson()));
        cs.setReferences(parseReferences(row.columnRefsJson()));
        cs.setHierarchyMode(HierarchyMode.fromValue(row.hierarchyMode()));
        cs.setReleaseChannels(parseChannels(row.releaseChannels()));
        cs.setSchemaVersion(row.schemaVersion());
        cs.setCurrentPublishedVersion(row.currentPublishedVersion());
        cs.setCreatedAt(toIsoString(row.createdAt()));
        cs.setCreatedBy(row.createdBy() == null ? null : row.createdBy().toString());
        cs.setUpdatedAt(toIsoString(row.updatedAt()));
        return cs;
    }

    public static CodeSetSchemaDto toSchema(SchemaRow row) {
        return new CodeSetSchemaDto(
                row.id().toString(),
                row.codesetId().toString(),
                row.version(),
                parseJsonSchemaMap(row.jsonSchemaText()),
                toIsoString(row.createdAt()),
                row.createdBy() == null ? null : row.createdBy().toString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static LocalizedLabel toLabels(String labelRu, String labelEn) {
        if (labelRu == null && labelEn == null) {
            return null;
        }
        LocalizedLabel l = new LocalizedLabel();
        l.setRu(labelRu);
        l.setEn(labelEn);
        return l;
    }

    private static List<String> toList(String[] arr) {
        if (arr == null) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(arr));
    }

    private static String toIsoString(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static KeySpec parseKeySpec(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, KeySpec.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse stored key_spec JSON: " + json, e);
        }
    }

    /** Парсит JSONB-массив column_refs в список {@link CodeSetRef}. NULL/"[]"/"" → пустой список. */
    private static List<CodeSetRef> parseReferences(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<CodeSetRef> refs = JSON.readValue(json, new TypeReference<List<CodeSetRef>>() {});
            return refs == null ? new ArrayList<>() : refs;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse stored column_refs JSON: " + json, e);
        }
    }

    private static java.util.Map<String, Object> parseJsonSchemaMap(String json) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        try {
            return JSON.readValue(json, new TypeReference<java.util.Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse stored json_schema JSON: " + json, e);
        }
    }

    private static List<ReleaseChannel> parseChannels(String[] channels) {
        if (channels == null) return List.of();
        List<ReleaseChannel> out = new ArrayList<>(channels.length);
        for (String c : channels) {
            out.add(ReleaseChannel.fromValue(c));
        }
        return out;
    }

    /** UUID parser с явной обёрткой, чтобы дать понятную диагностику. */
    public static UUID parseUuid(String s, String fieldName) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must be a UUID, got '" + s + "'", ex);
        }
    }

    /** Сериализация key_spec POJO → JSON-text для INSERT'а. */
    public static String writeJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise " + o.getClass().getSimpleName() + " to JSON", e);
        }
    }

    /** Утилита для экстракта строкового представления json_schema на POST/PUT. */
    public static String readJsonNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        ObjectNode obj = node.isObject() ? (ObjectNode) node : null;
        if (obj == null) {
            throw new IllegalArgumentException("json_schema must be a JSON object");
        }
        try {
            return JSON.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
