package bank.rdmmesh.ownership.internal;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.ownership.internal.dao.AssetOwnershipDao;
import bank.rdmmesh.ownership.internal.dao.AssetOwnershipDao.AssetOwnershipRow;
import bank.rdmmesh.spec.entity.AssetOwnership;

/**
 * Реализация {@link OwnershipPort} поверх {@code ownership.rdm_asset_ownership}.
 *
 * <p>{@link #applyChangeEvent} (E7) — атомарный UPSERT/DELETE по delta из OM webhook'а.
 * UNIQUE(asset_id, asset_type, om_user_id, role) гарантирует идемпотентность даже без
 * проверки {@code source_event_id} (тот используется для журнала на уровне HTTP-приёма
 * в {@link bank.rdmmesh.ownership.internal.dao.ProcessedEventDao}).
 *
 * <p>Политика «steward = expert» (SPEC §2.4: "для steward подобной семантики в OM нет —
 * steward = expert или отдельная политика"): на каждого добавленного/удалённого expert'а
 * мы дополнительно создаём/удаляем запись с role=STEWARD. Это позволяет {@code /tasks/my}
 * показывать ревью-задачи реальным OM-expert'ам без отдельного UI назначения steward'ов.
 */
public final class PostgresOwnershipPort implements OwnershipPort {

    private static final Logger log = LoggerFactory.getLogger(PostgresOwnershipPort.class);

    /** Признак «expert одновременно действует как steward». См. SPEC §2.4. */
    static final boolean EXPERT_ACTS_AS_STEWARD = true;

    private final Jdbi jdbi;

    public PostgresOwnershipPort(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public List<AssetOwnership> ownersOf(UUID assetId, String assetType) {
        return jdbi.withExtension(
                AssetOwnershipDao.class,
                dao -> dao.findByAsset(assetId, assetType).stream()
                        .map(PostgresOwnershipPort::toDto)
                        .toList());
    }

    @Override
    public Set<String> rolesOf(UUID assetId, UUID omUserId) {
        List<AssetOwnershipRow> rows = jdbi.withExtension(
                AssetOwnershipDao.class, dao -> dao.findRolesOf(assetId, omUserId));
        return rows.stream().map(AssetOwnershipRow::role).collect(Collectors.toSet());
    }

    @Override
    public AssetOwnership assignProvisionalOwner(
            UUID assetId, String assetType, UUID creatorOmUserId) {
        return jdbi.inTransaction(handle -> {
            AssetOwnershipDao dao = handle.attach(AssetOwnershipDao.class);
            dao.upsert(
                    assetId,
                    assetType,
                    creatorOmUserId,
                    "OWNER",
                    /* isProvisional */ true,
                    /* assignedBy */ creatorOmUserId,
                    /* sourceEventId */ null);
            log.info("ownership: provisional OWNER asset_id={} asset_type={} user={}",
                    assetId, assetType, creatorOmUserId);
            return toDto(dao.findOne(assetId, assetType, creatorOmUserId, "OWNER")
                    .orElseThrow(() -> new IllegalStateException(
                            "upsert не оставил строки для " + assetId)));
        });
    }

    @Override
    public void applyChangeEvent(
            UUID assetId, String assetType, OwnershipDelta delta, String sourceEventId) {
        // Backward-compatible: открываем свою tx и делегируем.
        jdbi.useTransaction(handle ->
                applyChangeEvent(handle, assetId, assetType, delta, sourceEventId));
    }

    @Override
    public void applyChangeEvent(
            Handle handle, UUID assetId, String assetType,
            OwnershipDelta delta, String sourceEventId) {
        // E14 round 5.2: работа на чужом handle. OwnershipWebhookService объединяет
        // delta-apply + recordIfAbsent в processed_om_event + (опц.) mirror UPSERT
        // в одну Postgres tx.
        AssetOwnershipDao dao = handle.attach(AssetOwnershipDao.class);

        // Removals идут первыми, чтобы при «move» (user был в одной коллекции, стал в
        // другой) последующий UPSERT не получил конфликт по uniq-ключу.
        for (UUID userId : delta.ownersRemoved()) {
            dao.delete(assetId, assetType, userId, "OWNER");
        }
        for (UUID userId : delta.expertsRemoved()) {
            dao.delete(assetId, assetType, userId, "EXPERT");
            if (EXPERT_ACTS_AS_STEWARD) {
                dao.delete(assetId, assetType, userId, "STEWARD");
            }
        }
        for (UUID userId : delta.approversRemoved()) {
            dao.delete(assetId, assetType, userId, "APPROVER");
        }

        // Additions: is_provisional=false (OM — мастер, его слово финальное), assigned_by
        // не известен из webhook'а (OM не отдаёт «кто назначил» в payload'е) — оставляем
        // null. source_event_id — последний event, который привёл к назначению этой роли.
        for (UUID userId : delta.ownersAdded()) {
            dao.upsert(assetId, assetType, userId, "OWNER", false, null, sourceEventId);
        }
        for (UUID userId : delta.expertsAdded()) {
            dao.upsert(assetId, assetType, userId, "EXPERT", false, null, sourceEventId);
            if (EXPERT_ACTS_AS_STEWARD) {
                dao.upsert(assetId, assetType, userId, "STEWARD", false, null, sourceEventId);
            }
        }
        for (UUID userId : delta.approversAdded()) {
            dao.upsert(assetId, assetType, userId, "APPROVER", false, null, sourceEventId);
        }

        log.info(
                "ownership: applyChangeEvent asset_id={} asset_type={} event={}"
                        + " owners(+{}/-{}) experts(+{}/-{}) approvers(+{}/-{})",
                assetId,
                assetType,
                sourceEventId,
                delta.ownersAdded().size(),
                delta.ownersRemoved().size(),
                delta.expertsAdded().size(),
                delta.expertsRemoved().size(),
                delta.approversAdded().size(),
                delta.approversRemoved().size());
    }

    /**
     * Считает delta по принципу «desired - current» для одной OM-коллекции (owners/experts/
     * approvers). Используется webhook receiver'ом, у которого на руках только массив
     * желаемых users — текущее состояние нужно поднять отсюда.
     */
    public OwnershipDelta computeDelta(
            UUID assetId,
            String assetType,
            Set<UUID> desiredOwners,
            Set<UUID> desiredExperts,
            Set<UUID> desiredApprovers) {
        return jdbi.withExtension(AssetOwnershipDao.class, dao ->
                computeDeltaInternal(dao, assetId, assetType,
                        desiredOwners, desiredExperts, desiredApprovers));
    }

    /**
     * E14 round 5.2 — Handle-overload. Read-only, но идёт через shared handle:
     * OwnershipWebhookService считает delta уже внутри tx, чтобы apply работал
     * по тому же snapshot'у БД и не получил «гонку» с concurrent ownership-write'ом.
     */
    public OwnershipDelta computeDelta(
            Handle handle,
            UUID assetId,
            String assetType,
            Set<UUID> desiredOwners,
            Set<UUID> desiredExperts,
            Set<UUID> desiredApprovers) {
        return computeDeltaInternal(handle.attach(AssetOwnershipDao.class),
                assetId, assetType, desiredOwners, desiredExperts, desiredApprovers);
    }

    private static OwnershipDelta computeDeltaInternal(
            AssetOwnershipDao dao,
            UUID assetId, String assetType,
            Set<UUID> desiredOwners, Set<UUID> desiredExperts, Set<UUID> desiredApprovers) {
        List<AssetOwnershipRow> current = dao.findByAsset(assetId, assetType);
        Set<UUID> currentOwners = collect(current, "OWNER");
        Set<UUID> currentExperts = collect(current, "EXPERT");
        Set<UUID> currentApprovers = collect(current, "APPROVER");
        return new OwnershipDelta(
                diffAdded(desiredOwners, currentOwners),
                diffRemoved(desiredOwners, currentOwners),
                diffAdded(desiredExperts, currentExperts),
                diffRemoved(desiredExperts, currentExperts),
                diffAdded(desiredApprovers, currentApprovers),
                diffRemoved(desiredApprovers, currentApprovers));
    }

    private static Set<UUID> collect(List<AssetOwnershipRow> rows, String role) {
        return rows.stream()
                .filter(r -> role.equals(r.role()))
                .map(AssetOwnershipRow::omUserId)
                .collect(Collectors.toSet());
    }

    private static Set<UUID> diffAdded(Set<UUID> desired, Set<UUID> current) {
        Set<UUID> out = new java.util.HashSet<>(desired);
        out.removeAll(current);
        return out;
    }

    private static Set<UUID> diffRemoved(Set<UUID> desired, Set<UUID> current) {
        Set<UUID> out = new java.util.HashSet<>(current);
        out.removeAll(desired);
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static AssetOwnership toDto(AssetOwnershipRow row) {
        AssetOwnership a = new AssetOwnership();
        a.setAssetId(row.assetId().toString());
        a.setAssetType(bank.rdmmesh.spec.entity.AssetOwnership.AssetType.fromValue(row.assetType()));
        a.setOmUserId(row.omUserId().toString());
        a.setRole(bank.rdmmesh.spec.entity.AssetOwnership.AssetRole.fromValue(row.role()));
        a.setIsProvisional(row.isProvisional());
        a.setAssignedAt(toIso(row.assignedAt()));
        a.setAssignedBy(row.assignedBy() == null ? null : row.assignedBy().toString());
        a.setSourceEventId(row.sourceEventId());
        return a;
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
