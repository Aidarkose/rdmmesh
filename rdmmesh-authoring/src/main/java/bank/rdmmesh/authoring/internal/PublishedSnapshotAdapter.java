package bank.rdmmesh.authoring.internal;

import java.util.Optional;
import java.util.UUID;

import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService;

/**
 * Реализация {@link PublishedSnapshotPort}. Stage 7c: canonical bytes считаются из
 * реляционного стора ({@code rd_data."<base>__draft"} версии) — единый алгоритм
 * {@link CanonicalSnapshot}, что и раньше для {@code code_item}, поэтому {@code content_hash}
 * прежних версий сохраняется (при равенстве данных). Publishing читает их до publish-CAS,
 * когда items версии ещё в {@code __draft}.
 */
public final class PublishedSnapshotAdapter implements PublishedSnapshotPort {

    private final RelationalStoreService relational;

    public PublishedSnapshotAdapter(RelationalStoreService relational) {
        this.relational = relational;
    }

    @Override
    public byte[] canonicalSnapshotBytes(UUID versionId) {
        return relational.canonicalBytes(versionId);
    }

    @Override
    public Optional<String> publishBlockReason(UUID versionId) {
        return relational.recordPublishBlockReason(versionId);
    }
}
