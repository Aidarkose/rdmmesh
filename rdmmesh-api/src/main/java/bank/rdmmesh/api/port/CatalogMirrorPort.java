package bank.rdmmesh.api.port;

import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Handle;

/**
 * Sync-контракт catalog'а для ownership-webhook'а (SPEC §2.4): mirror domain'ов из OM и
 * lookup CodeSet'а по FQN, который OM присылает в ChangeEvent для table.
 *
 * <p>Отдельный порт от {@link CatalogReadPort}, потому что эти операции — bulk write по
 * входному потоку из OM, а не запрос-ответ для соседних bounded contexts. Реализация
 * живёт в {@code rdmmesh-catalog} и проксирует {@code CatalogService}.
 */
public interface CatalogMirrorPort {

    /**
     * UPSERT domain'а по {@code om_domain_id}. Если запись уже есть — обновляются
     * mutable-поля; deleted_at сбрасывается (resurrect — тоже валидный путь, см. SPEC §2.4).
     */
    DomainMirrorResult upsertDomainFromOm(DomainMirror mirror);

    /**
     * E14 round 5.2 — Handle-overload. OwnershipWebhookService использует его,
     * чтобы UPSERT mirror'а и INSERT в processed_om_event попали в одну Postgres tx.
     * Закрывает split-tx между расхождением journal'а и mirror'а при сбое.
     */
    DomainMirrorResult upsertDomainFromOm(Handle handle, DomainMirror mirror);

    /**
     * Soft-delete по {@code om_domain_id} — проставляет {@code deleted_at = now()}. Реальное
     * удаление не делается, потому что downstream-CodeSet'ы держат FK на {@code domain.id}.
     */
    boolean softDeleteDomainByOmId(UUID omDomainId);

    /**
     * Lookup CodeSet'а по паре имён из FQN ({@code rdmmesh.<domain>.<codeset>}). Возвращает
     * id CodeSet'а, чтобы ownership-модуль мог вызвать {@code applyChangeEvent} с
     * корректным {@code asset_id}. Возвращает empty, если CodeSet не найден или soft-deleted.
     */
    Optional<UUID> findCodeSetIdByFqn(String domainName, String codesetName);

    /**
     * Snapshot domain'а в форме, нужной webhook'у. Локальный {@code id} (RDM UUID)
     * присваивается на стороне catalog'а — webhook им не управляет.
     */
    record DomainMirror(
            UUID omDomainId,
            String name,
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags) {}

    /** Что произошло после UPSERT. */
    record DomainMirrorResult(UUID id, UUID omDomainId, MirrorOp op) {}

    enum MirrorOp { CREATED, UPDATED, RESURRECTED, UNCHANGED }
}
