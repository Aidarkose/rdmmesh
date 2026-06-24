package bank.rdmmesh.api.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-side порт для получения детерминированного байтового представления items'ов версии.
 * Используется PublishingService (E6) для:
 * <ol>
 *   <li>вычисления {@code content_hash = SHA-256(canonical_bytes)} перед publish'ом;</li>
 *   <li>идемпотентного re-hash в verify-endpoint (SPEC §3.8 GET /versions/&#123;id&#125;/verify).</li>
 * </ol>
 *
 * <p><b>Канонизация</b> (соглашение, фиксированное на E6 — менять = ломать verify старых версий):
 * <ul>
 *   <li>JSON, UTF-8, без пробелов и переносов;</li>
 *   <li>ключи объектов отсортированы лексикографически;</li>
 *   <li>массив items отсортирован по {@code key_parts} (как JSON-массив строк/чисел в виде CompactJSON).</li>
 * </ul>
 *
 * <p>Реализация — в модуле {@code rdmmesh-authoring} (он владеет схемой). Для версии без items
 * возвращается канонический пустой документ ({@code {"items":[]}}).
 */
public interface PublishedSnapshotPort {

    /**
     * Канонические байты snapshot'а версии. Стабильны независимо от порядка вставки items
     * и порядка ключей в JSONB-колонках (parse → sort → serialize).
     *
     * @throws IllegalArgumentException если versionId не существует.
     */
    byte[] canonicalSnapshotBytes(UUID versionId);

    /**
     * Пред-проверка публикации (Stage 7 B): можно ли пересобрать {@code __current}
     * под эту версию, не нарушив материализованные FK/констрейнты rd_data.
     * Возвращает причину блокировки (если публиковать нельзя) либо empty (можно).
     * При блокировке реализация фиксирует видимый статус {@code BLOCKED} (Stage 7 A),
     * чтобы провал не был молчаливым.
     */
    Optional<String> publishBlockReason(UUID versionId);
}
