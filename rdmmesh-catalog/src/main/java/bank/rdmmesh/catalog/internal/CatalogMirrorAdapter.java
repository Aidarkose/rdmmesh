package bank.rdmmesh.catalog.internal;

import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.CatalogMirrorPort;
import bank.rdmmesh.catalog.internal.dao.CodeSetDao;
import bank.rdmmesh.catalog.internal.dao.DomainDao;
import bank.rdmmesh.catalog.internal.dao.DomainDao.DomainRow;

/**
 * Реализация {@link CatalogMirrorPort} — write-side контракт catalog'а для OM webhook'а.
 * Не идёт через {@code CatalogService}, потому что service-методы {@code createDomain} +
 * {@code patchDomain} рассчитаны на ручной {@code RDM_ADMIN}-flow, а здесь нужен один
 * UPSERT по {@code om_domain_id} с автоматическим resurrect-семантикой.
 */
public final class CatalogMirrorAdapter implements CatalogMirrorPort {

    private final Jdbi jdbi;

    public CatalogMirrorAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public DomainMirrorResult upsertDomainFromOm(DomainMirror mirror) {
        // Backward-compatible: открываем собственную tx и делегируем на handle-overload.
        return jdbi.inTransaction(handle -> upsertDomainFromOm(handle, mirror));
    }

    @Override
    public DomainMirrorResult upsertDomainFromOm(Handle handle, DomainMirror mirror) {
        // E14 round 5.2: работа на чужом handle. OwnershipWebhookService
        // объединяет mirror UPSERT + processed_om_event INSERT в одну Postgres tx.
        DomainDao dao = handle.attach(DomainDao.class);
        Optional<DomainRow> before = dao.findByOmId(mirror.omDomainId());

        int n = dao.upsertByOmId(
                mirror.omDomainId(),
                mirror.name(),
                mirror.displayName(),
                mirror.description(),
                mirror.labelRu(),
                mirror.labelEn(),
                mirror.tags() == null ? new String[0] : mirror.tags());
        if (n == 0) {
            throw new IllegalStateException(
                    "UPSERT catalog.domain returned 0 rows для om_domain_id=" + mirror.omDomainId());
        }

        DomainRow after = dao.findByOmId(mirror.omDomainId()).orElseThrow();
        MirrorOp op;
        if (before.isEmpty()) {
            op = MirrorOp.CREATED;
        } else if (before.get().deletedAt() != null) {
            op = MirrorOp.RESURRECTED;
        } else if (sameMutableFields(before.get(), after)) {
            op = MirrorOp.UNCHANGED;
        } else {
            op = MirrorOp.UPDATED;
        }
        return new DomainMirrorResult(after.id(), after.omDomainId(), op);
    }

    @Override
    public boolean softDeleteDomainByOmId(UUID omDomainId) {
        return jdbi.withExtension(DomainDao.class, dao -> dao.softDeleteByOmId(omDomainId)) > 0;
    }

    @Override
    public Optional<UUID> findCodeSetIdByFqn(String domainName, String codesetName) {
        return jdbi.withHandle(handle -> {
            var dom = handle.attach(DomainDao.class).findByName(domainName);
            if (dom.isEmpty()) return Optional.<UUID>empty();
            return handle.attach(CodeSetDao.class)
                    .findByDomainAndName(dom.get().id(), codesetName)
                    .map(cs -> cs.id());
        });
    }

    private static boolean sameMutableFields(DomainRow a, DomainRow b) {
        return java.util.Objects.equals(a.name(), b.name())
                && java.util.Objects.equals(a.displayName(), b.displayName())
                && java.util.Objects.equals(a.description(), b.description())
                && java.util.Objects.equals(a.labelRu(), b.labelRu())
                && java.util.Objects.equals(a.labelEn(), b.labelEn())
                && java.util.Arrays.equals(a.tags(), b.tags());
    }
}
