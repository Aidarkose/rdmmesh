package bank.rdmmesh.admin.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.admin.dto.AdminDomainView;
import bank.rdmmesh.admin.internal.dao.AdminDomainDao;
import bank.rdmmesh.admin.internal.dao.AdminDomainDao.AdminDomainRow;

public final class AdminDomainService {

    private static final Logger log = LoggerFactory.getLogger(AdminDomainService.class);

    private final Jdbi jdbi;

    public AdminDomainService(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public List<AdminDomainView> list() {
        return jdbi.withExtension(AdminDomainDao.class, dao -> {
            List<AdminDomainRow> rows = dao.findAll();
            return rows.stream().map(r -> toView(dao, r)).toList();
        });
    }

    public Optional<AdminDomainView> find(UUID id) {
        return jdbi.withExtension(AdminDomainDao.class, dao ->
                dao.findById(id).map(r -> toView(dao, r)));
    }

    public AdminDomainView create(NewDomain req) {
        return jdbi.inTransaction(handle -> {
            AdminDomainDao dao = handle.attach(AdminDomainDao.class);

            // Уникальность name (CHECK regex проверяется БД).
            if (dao.findByName(req.name()).isPresent()) {
                throw new IllegalArgumentException(
                        "Domain '" + req.name() + "' already exists");
            }
            // Уникальность om_domain_id (если LINKED).
            if (req.omDomainId() != null && dao.findByOmId(req.omDomainId()).isPresent()) {
                throw new IllegalArgumentException(
                        "Domain with om_domain_id=" + req.omDomainId() + " already exists");
            }

            String master = req.omDomainId() == null ? "RDM" : "LINKED";
            UUID id = UUID.randomUUID();
            int n = dao.insert(
                    id, req.omDomainId(), req.name(),
                    req.displayName(), req.description(),
                    req.labelRu(), req.labelEn(),
                    req.tags() == null ? new String[0] : req.tags(),
                    master);
            if (n != 1) {
                throw new IllegalStateException("INSERT catalog.domain returned " + n);
            }
            log.info("admin: создан domain id={} name={} master={}", id, req.name(), master);
            return toView(dao, dao.findById(id).orElseThrow());
        });
    }

    public Optional<AdminDomainView> patch(UUID id, DomainPatch patch) {
        return jdbi.inTransaction(handle -> {
            AdminDomainDao dao = handle.attach(AdminDomainDao.class);
            AdminDomainRow existing = dao.findById(id).orElse(null);
            if (existing == null) {
                return Optional.<AdminDomainView>empty();
            }
            if ("OM".equals(existing.master())) {
                throw new IllegalStateException(
                        "Cannot patch OM-mastered domain — edit in OpenMetadata");
            }
            int n = dao.patch(id,
                    patch.displayName(), patch.description(),
                    patch.labelRu(), patch.labelEn(), patch.tags());
            if (n == 0) return Optional.<AdminDomainView>empty();
            return dao.findById(id).map(r -> toView(dao, r));
        });
    }

    public AdminDomainView rename(UUID id, String newName) {
        return jdbi.inTransaction(handle -> {
            AdminDomainDao dao = handle.attach(AdminDomainDao.class);
            AdminDomainRow existing = dao.findById(id).orElseThrow(() ->
                    new IllegalArgumentException("Domain not found: " + id));
            if (!"RDM".equals(existing.master())) {
                throw new IllegalStateException(
                        "Cannot rename: domain master=" + existing.master()
                                + " (rename доступен только для RDM-локальных)");
            }
            if (dao.findByName(newName).isPresent()) {
                throw new IllegalArgumentException("Name '" + newName + "' already taken");
            }
            int n = dao.rename(id, newName);
            if (n != 1) {
                throw new IllegalStateException("UPDATE rename returned " + n);
            }
            log.info("admin: переименован domain id={} → name={}", id, newName);
            return toView(dao, dao.findById(id).orElseThrow());
        });
    }

    /** Hard-delete только для RDM-master доменов без активных codeset'ов. */
    public void delete(UUID id) {
        jdbi.useTransaction(handle -> {
            AdminDomainDao dao = handle.attach(AdminDomainDao.class);
            AdminDomainRow existing = dao.findById(id).orElseThrow(() ->
                    new IllegalArgumentException("Domain not found: " + id));
            if (!"RDM".equals(existing.master())) {
                throw new IllegalStateException(
                        "Cannot delete: master=" + existing.master()
                                + ". Используйте OpenMetadata для OM-доменов или :unlink-from-om сначала.");
            }
            long codesets = dao.countAllCodeSets(id);
            if (codesets > 0) {
                long active = dao.countActiveCodeSets(id);
                throw new IllegalStateException(
                        "Cannot delete: domain has " + codesets + " codeset(s)"
                                + " (" + active + " active, " + (codesets - active) + " archived)."
                                + " FK ON DELETE RESTRICT блокирует удаление домена с историей справочников.");
            }
            int n = dao.hardDelete(id);
            if (n != 1) {
                throw new IllegalStateException("DELETE returned " + n);
            }
            log.info("admin: удалён domain id={}", id);
        });
    }

    public AdminDomainView linkToOm(UUID id, UUID omDomainId) {
        return jdbi.inTransaction(handle -> {
            AdminDomainDao dao = handle.attach(AdminDomainDao.class);
            AdminDomainRow existing = dao.findById(id).orElseThrow(() ->
                    new IllegalArgumentException("Domain not found: " + id));
            if (!"RDM".equals(existing.master())) {
                throw new IllegalStateException(
                        "Link-to-OM применим только к RDM-локальным; текущий master=" + existing.master());
            }
            if (dao.findByOmId(omDomainId).isPresent()) {
                throw new IllegalArgumentException(
                        "om_domain_id=" + omDomainId + " уже привязан к другому domain'у");
            }
            // TODO E18.3: валидация om_domain_id через OM REST API (синхронный lookup).
            // Пока admin берёт UUID на свой риск; если не существует в OM —
            // webhook'и просто не будут приходить, но запись валидна.
            int n = dao.linkToOm(id, omDomainId);
            if (n != 1) {
                throw new IllegalStateException("UPDATE link returned " + n);
            }
            log.info("admin: domain id={} слинкован с om_domain_id={}", id, omDomainId);
            return toView(dao, dao.findById(id).orElseThrow());
        });
    }

    public AdminDomainView unlinkFromOm(UUID id) {
        return jdbi.inTransaction(handle -> {
            AdminDomainDao dao = handle.attach(AdminDomainDao.class);
            AdminDomainRow existing = dao.findById(id).orElseThrow(() ->
                    new IllegalArgumentException("Domain not found: " + id));
            if (!"LINKED".equals(existing.master())) {
                throw new IllegalStateException(
                        "Unlink применим только к LINKED-доменам; текущий master=" + existing.master());
            }
            int n = dao.unlinkFromOm(id);
            if (n != 1) {
                throw new IllegalStateException("UPDATE unlink returned " + n);
            }
            log.info("admin: domain id={} отвязан от OM", id);
            return toView(dao, dao.findById(id).orElseThrow());
        });
    }

    private static AdminDomainView toView(AdminDomainDao dao, AdminDomainRow r) {
        long codesets = dao.countActiveCodeSets(r.id());
        return new AdminDomainView(
                r.id().toString(),
                AdminDomainView.uuidOrNull(r.omDomainId()),
                r.name(),
                r.displayName(),
                r.description(),
                r.labelRu(),
                r.labelEn(),
                r.tags() == null ? List.of() : List.of(r.tags()),
                r.master(),
                r.localOverridesJson(),
                r.externalRefsJson(),
                r.lastOmSyncAt(),
                r.deletedInOmAt(),
                codesets,
                r.createdAt(),
                r.updatedAt(),
                r.deletedAt());
    }

    public record NewDomain(
            UUID omDomainId,
            String name,
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags) {}

    public record DomainPatch(
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags) {}
}
