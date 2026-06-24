package bank.rdmmesh.authoring.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.authoring.internal.dao.CodeSetVersionDao.VersionRow;
import bank.rdmmesh.authoring.resource.CodeItemDto;
import bank.rdmmesh.spec.entity.CodeSetVersion;
import bank.rdmmesh.spec.entity.CodeSetVersion.ReleaseChannel;
import bank.rdmmesh.spec.api.TransitionRequest.VersionStatus;

/**
 * Чистые «row → DTO» преобразования. {@link CodeSetVersion} POJO годится для возврата
 * из REST-эндпоинтов как есть; для CodeItem'ов используем собственный {@link CodeItemDto},
 * потому что у jsonschema2pojo есть проблема с произвольными {@code attributes}
 * (см. CodeSet/JsonSchema-историю в handoff'е E3 §1.4).
 */
public final class AuthoringMappers {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private AuthoringMappers() {}

    public static CodeSetVersion toVersion(VersionRow row) {
        CodeSetVersion v = new CodeSetVersion();
        v.setId(row.id().toString());
        v.setCodesetId(row.codesetId().toString());
        v.setVersion(row.version());
        v.setStatus(VersionStatus.fromValue(row.status()));
        v.setSchemaVersion(row.schemaVersion());
        v.setReleaseChannel(ReleaseChannel.fromValue(row.releaseChannel()));
        v.setEffectiveFrom(row.effectiveFrom() == null ? null : row.effectiveFrom().toString());
        v.setEffectiveTo(row.effectiveTo() == null ? null : row.effectiveTo().toString());
        v.setSystemFrom(toIso(row.systemFrom()));
        v.setSystemTo(toIso(row.systemTo()));
        v.setCreatedAt(toIso(row.createdAt()));
        v.setCreatedBy(row.createdBy() == null ? null : row.createdBy().toString());
        v.setApprovedBy(row.approvedBy() == null ? null : row.approvedBy().toString());
        v.setPublishedBy(row.publishedBy() == null ? null : row.publishedBy().toString());
        v.setPublishedAt(toIso(row.publishedAt()));
        v.setDeprecatedAt(toIso(row.deprecatedAt()));
        v.setContentHash(row.contentHash());
        v.setApprovalSignature(row.approvalSignature());
        v.setOwnerWasProvisional(Boolean.TRUE.equals(row.ownerWasProvisional()));
        v.setItemCount(row.itemCount());
        return v;
    }

    private static String toIso(java.time.Instant i) {
        if (i == null) return null;
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC).toString();
    }

    public static List<String> parseList(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return JSON.readValue(text, LIST_STRING);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse JSON list: " + text, e);
        }
    }

    public static Map<String, Object> parseMap(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return JSON.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse JSON map: " + text, e);
        }
    }

    public static String writeJson(Object value) {
        if (value == null) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise value to JSON", e);
        }
    }

    /** Linked-копия Map'а — чтобы service мог писать в неё, не мутируя клиентский payload. */
    public static Map<String, Object> safeMapCopy(Map<String, Object> in) {
        if (in == null) return new LinkedHashMap<>();
        return new LinkedHashMap<>(in);
    }

    public static List<String> safeListCopy(List<String> in) {
        if (in == null) return null;
        return new ArrayList<>(in);
    }
}
