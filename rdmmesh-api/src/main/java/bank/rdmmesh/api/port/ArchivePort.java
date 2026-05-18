package bank.rdmmesh.api.port;

import java.time.OffsetDateTime;

/**
 * Port для immutable-архива (SPEC §3.8 — «Параллельный сток в S3 (immutable
 * bucket) — V2»; E14 round 10). Абстрагирует object-store: dev-adapter —
 * RustFS (Apache-2.0, S3-совместимый), prod — любой S3-API.
 *
 * <p><b>Hexagonal.</b> Интерфейс — в rdmmesh-api; реализация
 * ({@code RustFsArchiveAdapter}, minio-client) — в composition-root'е
 * rdmmesh-app. {@code rdmmesh-audit} (который оркеструет архивацию сегментов
 * audit_log) видит только этот порт — он не имеет права тянуть S3-клиент
 * (ArchUnit {@code audit_only_depends_on_api_or_spec}).
 *
 * <p><b>Disabled-by-default.</b> Если archive-endpoint не сконфигурирован
 * ({@code RDM_ARCHIVE_ENDPOINT} пуст) — {@link #enabled()} = false, методы
 * бросают {@link IllegalStateException}. Сервис без RustFS не падает (как
 * OM-lookup в E2): архивация — опциональный ops-flow, не hot-path.
 *
 * <p><b>Immutability.</b> {@link #putImmutable} пытается выставить S3
 * Object-Lock retention (GOVERNANCE) до {@code retainUntil}. Если store не
 * поддерживает Object-Lock API — объект всё равно записан, но
 * {@link ArchiveResult#retentionApplied()} = false и {@code note} объясняет;
 * вызывающий код фиксирует это в манифесте (честно, без молчаливой потери
 * WORM-гарантии).
 */
public interface ArchivePort {

    /** false → adapter не сконфигурирован; вызовы put/exists бросят ISE. */
    boolean enabled();

    /**
     * Записать объект как immutable. Идемпотентно по {@code objectKey}
     * (повторная запись того же сегмента допускается до commit retention;
     * после lock — store отвергнет, что и есть требуемая WORM-семантика).
     *
     * @param objectKey  ключ в bucket'е (напр. {@code audit/2026/05/segment.ndjson}).
     * @param content    байты сегмента (уже сериализованы детерминированно).
     * @param sha256Hex  SHA-256 контента (хранится в манифесте для verify).
     * @param retainUntil до какого момента объект защищён от удаления.
     */
    ArchiveResult putImmutable(
            String objectKey, byte[] content, String sha256Hex, OffsetDateTime retainUntil);

    /** true, если объект с таким ключом существует (drop-guard verify). */
    boolean exists(String objectKey);

    /**
     * Скачать объект целиком (для compliance-verify: пересчёт SHA-256 vs
     * {@code archive_manifest}). Сегменты — единицы–десятки KB (ndjson
     * месячного audit'а), in-memory приемлемо.
     *
     * @throws IllegalStateException если объект отсутствует / store недоступен.
     */
    byte[] get(String objectKey);

    /**
     * @param objectKey        фактический ключ объекта в store.
     * @param etag             ETag/идентификатор версии, отданный store'ом.
     * @param sizeBytes        размер записанного контента.
     * @param retentionApplied удалось ли выставить Object-Lock retention.
     * @param note             пояснение (особенно если retentionApplied=false).
     */
    record ArchiveResult(
            String bucket,
            String objectKey,
            String etag,
            long sizeBytes,
            boolean retentionApplied,
            String note) {}
}
