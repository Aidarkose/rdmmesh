package bank.rdmmesh.api.port;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Handle;

import bank.rdmmesh.spec.entity.AssetOwnership;

/**
 * Resolves "who owns / stewards / approves what?" by reading {@code rdm_asset_ownership},
 * which is populated by the OpenMetadata ownership webhook (SPEC §2.4) and bootstrap
 * provisional inserts.
 *
 * <p>RDM never asks OM synchronously; this port is the only place asset-level role
 * lookups happen.
 */
public interface OwnershipPort {

    /** All assignments for an asset across roles. */
    List<AssetOwnership> ownersOf(UUID assetId, String assetType);

    /** Roles a particular user holds on a particular asset (may be empty). */
    Set<String> rolesOf(UUID assetId, UUID omUserId);

    /**
     * Bootstrap provisional owner: invoked by {@code rdmmesh-catalog} right after a
     * CodeSet is created so that the creator can act as OWNER until the OM ingestion +
     * webhook round-trip arrives. Idempotent.
     */
    AssetOwnership assignProvisionalOwner(UUID assetId, String assetType, UUID creatorOmUserId);

    /**
     * Apply an OM ChangeEvent delta (resolved already by the webhook receiver) atomically.
     * Implementations should be idempotent on {@code source_event_id}.
     */
    void applyChangeEvent(UUID assetId, String assetType, OwnershipDelta delta, String sourceEventId);

    /**
     * E14 round 5.2 — Handle-overload. OwnershipWebhookService использует его,
     * чтобы delta-apply + recordIfAbsent журнал попали в одну Postgres tx.
     * Закрывает split-tx между ownership-write и journal'ом.
     */
    void applyChangeEvent(Handle handle, UUID assetId, String assetType,
                          OwnershipDelta delta, String sourceEventId);

    /** Difference between desired and current state for a single asset. */
    record OwnershipDelta(
            Set<UUID> ownersAdded,
            Set<UUID> ownersRemoved,
            Set<UUID> expertsAdded,
            Set<UUID> expertsRemoved,
            Set<UUID> approversAdded,
            Set<UUID> approversRemoved) {}
}
