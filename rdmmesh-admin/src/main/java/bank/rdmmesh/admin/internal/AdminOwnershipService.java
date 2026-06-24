package bank.rdmmesh.admin.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.admin.dto.AdminOwnershipView;
import bank.rdmmesh.admin.internal.dao.AdminOwnershipDao;
import bank.rdmmesh.admin.internal.dao.AdminOwnershipDao.OwnershipRow;

public final class AdminOwnershipService {

    private static final Logger log = LoggerFactory.getLogger(AdminOwnershipService.class);

    private static final Set<String> ALLOWED_ASSET_TYPES = Set.of("DOMAIN", "CODESET");
    private static final Set<String> ALLOWED_ROLES =
            Set.of("OWNER", "STEWARD", "EXPERT", "APPROVER");

    private final Jdbi jdbi;

    public AdminOwnershipService(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public List<AdminOwnershipView> listForAsset(UUID assetId, String assetType) {
        assertAssetType(assetType);
        return jdbi.withExtension(AdminOwnershipDao.class, dao ->
                dao.findActive(assetId, assetType).stream()
                        .map(AdminOwnershipService::toView)
                        .toList());
    }

    public AdminOwnershipView assign(NewAssignment req, UUID actor) {
        assertAssetType(req.assetType());
        assertRole(req.role());

        return jdbi.inTransaction(handle -> {
            AdminOwnershipDao dao = handle.attach(AdminOwnershipDao.class);
            UUID id = UUID.randomUUID();
            dao.assignLocal(
                    id,
                    req.assetId(), req.assetType(),
                    req.omUserId(), req.role(),
                    req.pinnedLocal(),
                    actor);
            log.info("admin: assigned ownership asset={}/{} user={} role={} actor={}",
                    req.assetType(), req.assetId(), req.omUserId(), req.role(), actor);
            // Re-read через UNIQUE (asset_id, asset_type, om_user_id, role) — он гарантирует один tuple.
            return dao.findActive(req.assetId(), req.assetType()).stream()
                    .filter(r -> r.omUserId().equals(req.omUserId()) && r.role().equals(req.role()))
                    .findFirst()
                    .map(AdminOwnershipService::toView)
                    .orElseThrow(() -> new IllegalStateException("assign failed to materialize"));
        });
    }

    public Optional<AdminOwnershipView> setPinned(UUID id, boolean pinned) {
        return jdbi.inTransaction(handle -> {
            AdminOwnershipDao dao = handle.attach(AdminOwnershipDao.class);
            int n = dao.setPinned(id, pinned);
            if (n == 0) return Optional.<AdminOwnershipView>empty();
            return dao.findById(id).map(AdminOwnershipService::toView);
        });
    }

    public void delete(UUID id) {
        jdbi.useTransaction(handle -> {
            AdminOwnershipDao dao = handle.attach(AdminOwnershipDao.class);
            int n = dao.softDelete(id);
            if (n != 1) {
                throw new IllegalArgumentException("Ownership row not found or already deleted: " + id);
            }
            log.info("admin: soft-deleted ownership id={}", id);
        });
    }

    private static void assertAssetType(String type) {
        if (!ALLOWED_ASSET_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "asset_type must be DOMAIN or CODESET, got " + type);
        }
    }

    private static void assertRole(String role) {
        if (!ALLOWED_ROLES.contains(role)) {
            throw new IllegalArgumentException(
                    "role must be one of " + ALLOWED_ROLES + ", got " + role);
        }
    }

    private static AdminOwnershipView toView(OwnershipRow r) {
        return new AdminOwnershipView(
                r.id().toString(),
                r.assetId().toString(),
                r.assetType(),
                r.omUserId().toString(),
                r.role(),
                r.origin(),
                r.pinnedLocal(),
                r.isProvisional(),
                r.assignedAt(),
                r.assignedByUserId() == null ? null : r.assignedByUserId().toString());
    }

    public record NewAssignment(
            UUID assetId,
            String assetType,
            UUID omUserId,
            String role,
            boolean pinnedLocal) {}
}
