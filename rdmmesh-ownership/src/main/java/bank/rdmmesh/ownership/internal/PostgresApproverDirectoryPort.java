package bank.rdmmesh.ownership.internal;

import java.util.List;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.ApproverDirectoryPort;
import bank.rdmmesh.ownership.internal.dao.DomainRoleDirectoryDao;
import bank.rdmmesh.ownership.internal.dao.DomainRoleDirectoryDao.ApproverRow;

/**
 * Реализация {@link ApproverDirectoryPort} поверх
 * {@code ownership.domain_role_directory} (BR-21/BR-22, handoff E17).
 *
 * <p>{@link #reload} — полная замена: {@code TRUNCATE} + построчный INSERT
 * в <b>одной</b> Postgres-tx (атомарная подмена снапшота). Если tx упадёт —
 * старый справочник остаётся целым (rollback).
 */
public final class PostgresApproverDirectoryPort implements ApproverDirectoryPort {

    private static final Logger log =
            LoggerFactory.getLogger(PostgresApproverDirectoryPort.class);

    private final Jdbi jdbi;

    public PostgresApproverDirectoryPort(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public boolean isAssignable(UUID domainId, String role, UUID omUserId) {
        if (domainId == null || role == null || omUserId == null) {
            return false;
        }
        return jdbi.withExtension(DomainRoleDirectoryDao.class,
                d -> d.exists(domainId, role, omUserId).isPresent());
    }

    @Override
    public List<Approver> approversOf(UUID domainId, String role) {
        List<ApproverRow> rows = jdbi.withExtension(DomainRoleDirectoryDao.class,
                d -> d.approversOf(domainId, role));
        return rows.stream()
                .map(r -> new Approver(r.omUserId(), r.username(), r.displayName(), r.role()))
                .toList();
    }

    @Override
    public int reload(List<DirectoryEntry> entries) {
        List<DirectoryEntry> safe = entries == null ? List.of() : entries;
        int inserted = jdbi.inTransaction(handle -> {
            DomainRoleDirectoryDao dao = handle.attach(DomainRoleDirectoryDao.class);
            dao.truncate();
            int n = 0;
            for (DirectoryEntry e : safe) {
                if (e.omDomainId() == null || e.role() == null || e.omUserId() == null) {
                    continue;
                }
                n += dao.insertResolvingDomain(
                        e.omDomainId(),
                        e.role(),
                        e.omUserId(),
                        e.username() == null ? e.omUserId().toString() : e.username(),
                        e.displayName(),
                        "LOCAL_SEED");
            }
            return n;
        });
        log.info("domain_role_directory reload: {} entries received, {} rows inserted "
                + "(full replace, BR-22)", safe.size(), inserted);
        return inserted;
    }

    @Override
    public void addLocal(
            UUID domainId, String role, UUID omUserId, String username, String displayName) {
        String safeUsername = username == null || username.isBlank()
                ? omUserId.toString()
                : username;
        jdbi.useExtension(DomainRoleDirectoryDao.class, d -> d.insertByDomainId(
                domainId, role, omUserId, safeUsername, displayName, "RDM_ADMIN_LOCAL"));
        log.info("domain_role_directory addLocal: domain={} role={} user={} (RDM_ADMIN_LOCAL)",
                domainId, role, omUserId);
    }

    @Override
    public boolean removeLocal(UUID domainId, String role, UUID omUserId) {
        int deleted = jdbi.withExtension(DomainRoleDirectoryDao.class,
                d -> d.deleteEntry(domainId, role, omUserId));
        return deleted > 0;
    }
}
