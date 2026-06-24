package bank.rdmmesh.admin.internal;

import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.admin.internal.dao.AdminCodeSetDao;

public final class AdminCodeSetService {

    private static final Logger log = LoggerFactory.getLogger(AdminCodeSetService.class);

    private final Jdbi jdbi;

    public AdminCodeSetService(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void rename(UUID id, String newName, boolean keepAlias) {
        jdbi.useTransaction(handle -> {
            int n = handle.attach(AdminCodeSetDao.class).rename(id, newName, keepAlias);
            if (n == 0) {
                throw new IllegalArgumentException(
                        "CodeSet not found or already deleted: " + id);
            }
            log.info("admin: переименован codeset id={} → name={} (keepAlias={})",
                    id, newName, keepAlias);
        });
    }

    public void delete(UUID id, boolean forceArchive) {
        jdbi.useTransaction(handle -> {
            AdminCodeSetDao dao = handle.attach(AdminCodeSetDao.class);
            long published = dao.countPublishedVersions(id);
            if (published > 0 && !forceArchive) {
                throw new IllegalStateException(
                        "CodeSet has " + published + " PUBLISHED version(s)."
                                + " Use force_archive=true to soft-delete anyway.");
            }
            int n = dao.softDelete(id);
            if (n == 0) {
                throw new IllegalArgumentException(
                        "CodeSet not found or already deleted: " + id);
            }
            log.info("admin: soft-deleted codeset id={} force={}", id, forceArchive);
        });
    }
}
