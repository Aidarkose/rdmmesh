package bank.rdmmesh.audit.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Pure-функции для криптографической hash-chain audit-журнала (E14 round 1).
 *
 * <p><b>Канонизация payload'а.</b> Hash chain должна быть пересчитываемой и из
 * Java (write-side), и из SQL (verify-side, теоретически — readable админу
 * напрямую через psql). Поэтому payload сериализуется в byte-stable форму:
 * sorted-by-keys JSON, без whitespace, с детерминированным числовым
 * представлением (Jackson по умолчанию даёт это для скаляров; для FLOAT
 * используется обычный toString — поведение Jackson'а версии 2.x стабильно
 * между релизами).
 *
 * <p><b>Формат `occurred_at` в hash-input.</b>
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'} — ISO-8601 instant в UTC, 6-цифровая
 * микросекундная точность. Совпадает с SQL-формулой
 * {@code to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')}.
 *
 * <p><b>Алгоритм hash.</b>
 * <pre>
 *   canonical_input = coalesce(prev_hash, "")
 *                  + "|" + event_id
 *                  + "|" + event_type
 *                  + "|" + payload_canonical
 *                  + "|" + occurred_at_iso_utc
 *   entry_hash      = sha256_hex(canonical_input UTF-8)
 * </pre>
 * Первая запись цепочки имеет {@code prev_hash = null}, в hash-input уходит
 * пустая строка (не строка из 64 нулей — намеренно, чтобы visually отличать
 * корень).
 *
 * <p><b>Stateless.</b> Класс не держит мутабельного состояния, методы
 * thread-safe; единственный {@link ObjectMapper} собран в конструкторе и потом
 * только используется на read. {@link #defaultMapper()} — статическая factory
 * с правильно сконфигурированным mapper'ом.
 */
public final class AuditChainHasher {

    /** Padded 6-микросекунд UTC-формат. Совпадает с SQL to_char(..., 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'). */
    public static final DateTimeFormatter CANONICAL_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private static final String SEP = "|";

    private final ObjectMapper canonicalMapper;

    public AuditChainHasher(ObjectMapper canonicalMapper) {
        this.canonicalMapper = canonicalMapper;
    }

    /**
     * Стандартный canonical-mapper: sorted keys, no null fields, JavaTime
     * через ISO-8601-строки. Используется по умолчанию; внешний код может
     * подать свой mapper, если payload содержит специфические типы.
     */
    public static ObjectMapper defaultMapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
        // JSR-310 (Instant, OffsetDateTime, …) подключается через SPI —
        // dropwizard-jackson транзитивно тянет jackson-datatype-jsr310.
        mapper.findAndRegisterModules();
        return mapper;
    }

    /** Сериализует {@code payload} в byte-stable JSON. {@code null} → {@code "null"}. */
    public String canonicalPayload(Object payload) {
        try {
            return canonicalMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "audit: payload serialisation failed (chain would be broken)", e);
        }
    }

    /**
     * Считает {@code entry_hash} от собранного canonical_input. Параметры
     * передаются по отдельности, чтобы тесты могли проверить, что компоненты
     * корректно вытекают в hash без скрытых преобразований.
     *
     * @param prevHash         hex64 от предыдущей записи; {@code null} только для самой первой строки цепочки.
     * @param eventId          UUID события (тот же, что в {@code event_id} колонке).
     * @param eventType        строка из {@link AuditEventClassifier} (WORKFLOW_TRANSITION, …).
     * @param payloadCanonical результат {@link #canonicalPayload}.
     * @param occurredAt       момент события; усекается до микросекунд и форматируется в UTC.
     * @return hex64 SHA-256.
     */
    public String computeEntryHash(
            String prevHash,
            UUID eventId,
            String eventType,
            String payloadCanonical,
            OffsetDateTime occurredAt) {
        String canon = (prevHash == null ? "" : prevHash)
                + SEP + eventId
                + SEP + eventType
                + SEP + payloadCanonical
                + SEP + formatOccurredAt(occurredAt);
        return sha256Hex(canon);
    }

    /** UTC, микросекундная точность, ISO-8601 instant с суффиксом 'Z'. */
    public static String formatOccurredAt(OffsetDateTime t) {
        return t.atZoneSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS)
                .format(CANONICAL_TIMESTAMP);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JRE", e);
        }
    }
}
