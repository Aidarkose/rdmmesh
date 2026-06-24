package bank.rdmmesh.admin.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.admin.dto.AdminDeletionRequestView;
import bank.rdmmesh.admin.internal.dao.AdminCodeSetDao;
import bank.rdmmesh.admin.internal.dao.AdminDeletionRequestDao;
import bank.rdmmesh.admin.internal.dao.AdminDeletionRequestDao.BaseRow;
import bank.rdmmesh.admin.internal.dao.AdminDeletionRequestDao.DetailedRow;

/**
 * Бизнес-операции workflow заявок на удаление CodeSet'ов (E22).
 *
 * <p>Состояния: {@code PENDING → APPROVED | REJECTED | CANCELLED} (terminal).
 *
 * <p>Approve == soft-delete CodeSet (deleted_at = now()); hard-delete не делается
 * никогда — IFRS9 retention (§3.7). Service переиспользует существующий
 * {@link AdminCodeSetService#delete(UUID, boolean)} для самого soft-delete'а,
 * чтобы guard на PUBLISHED-версии оставался единственным источником истины.
 *
 * <p>Self-approval prevention: admin, который заявку подал, не может её approve.
 * Должен попросить другого admin'а.
 */
public final class AdminDeletionRequestService {

    private static final Logger log = LoggerFactory.getLogger(AdminDeletionRequestService.class);

    static final int REASON_MIN = 10;
    static final int REASON_MAX = 2000;

    private final Jdbi jdbi;
    private final AdminCodeSetService codeSets;

    public AdminDeletionRequestService(Jdbi jdbi, AdminCodeSetService codeSets) {
        this.jdbi = jdbi;
        this.codeSets = codeSets;
    }

    // ── Author-facing ───────────────────────────────────────────────────────────

    /**
     * Author подаёт заявку на удаление CodeSet'а.
     *
     * @throws IllegalArgumentException codeset не найден или soft-deleted
     * @throws IllegalStateException    уже есть PENDING на этот codeset
     * @throws ValidationException      reason пустой или вне 10..2000
     */
    public AdminDeletionRequestView submit(UUID codesetId, String reason, UUID requestedBy) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < REASON_MIN || trimmed.length() > REASON_MAX) {
            throw new ValidationException(
                    "reason length must be between " + REASON_MIN + " and " + REASON_MAX + " characters");
        }
        return jdbi.inTransaction(handle -> {
            AdminCodeSetDao codeSetDao = handle.attach(AdminCodeSetDao.class);
            // softDelete возвращает 0 если codeset не найден ИЛИ уже deleted; нам нужно
            // отличить «нет codeset'а» от «уже deleted» — делаем явный SELECT.
            Boolean codesetDeleted = handle.createQuery(
                            "SELECT deleted_at IS NOT NULL FROM catalog.code_set WHERE id = :id")
                    .bind("id", codesetId)
                    .mapTo(Boolean.class)
                    .findOne()
                    .orElse(null);
            if (codesetDeleted == null) {
                throw new IllegalArgumentException("CodeSet not found: " + codesetId);
            }
            if (codesetDeleted) {
                throw new IllegalArgumentException("CodeSet is already deleted: " + codesetId);
            }

            AdminDeletionRequestDao dao = handle.attach(AdminDeletionRequestDao.class);
            UUID id = UUID.randomUUID();
            try {
                int n = dao.insert(id, codesetId, requestedBy, trimmed);
                if (n != 1) throw new IllegalStateException("INSERT deletion_request returned " + n);
            } catch (UnableToExecuteStatementException e) {
                // partial unique cs_del_req_one_pending_per_codeset_ix → 23505
                if (isUniqueViolation(e)) {
                    throw new IllegalStateException(
                            "There is already a PENDING deletion request for this CodeSet");
                }
                throw e;
            }
            log.info("admin: deletion-request submit id={} codeset_id={} by={}", id, codesetId, requestedBy);
            return toView(dao.findByIdDetailed(id).orElseThrow());
        });
    }

    public List<AdminDeletionRequestView> listMy(UUID requestedBy) {
        return jdbi.withExtension(AdminDeletionRequestDao.class, dao -> dao.listByRequestedBy(requestedBy).stream()
                .map(AdminDeletionRequestService::toView)
                .toList());
    }

    /** Author отзывает свою PENDING-заявку. Не свою — 403; не PENDING — 409. */
    public void cancel(UUID requestId, UUID actor) {
        jdbi.useTransaction(handle -> {
            AdminDeletionRequestDao dao = handle.attach(AdminDeletionRequestDao.class);
            BaseRow row = dao.findById(requestId)
                    .orElseThrow(() -> new IllegalArgumentException("Deletion request not found: " + requestId));
            if (!row.requestedBy().equals(actor)) {
                throw new ForbiddenException("Only the requester can cancel this deletion request");
            }
            if (!"PENDING".equals(row.status())) {
                throw new IllegalStateException("Cannot cancel: current status is " + row.status());
            }
            int n = dao.transitionFromPending(requestId, "CANCELLED", actor, null);
            if (n == 0) {
                throw new IllegalStateException("Concurrent transition: request is no longer PENDING");
            }
            log.info("admin: deletion-request cancel id={} by={}", requestId, actor);
        });
    }

    // ── Admin-facing ────────────────────────────────────────────────────────────

    public List<AdminDeletionRequestView> listByStatus(String status) {
        String s = status == null || status.isBlank() ? "PENDING" : status.toUpperCase();
        if (!List.of("PENDING", "APPROVED", "REJECTED", "CANCELLED").contains(s)) {
            throw new IllegalArgumentException("Unknown status filter: " + s);
        }
        return jdbi.withExtension(AdminDeletionRequestDao.class, dao -> dao.listByStatus(s).stream()
                .map(AdminDeletionRequestService::toView)
                .toList());
    }

    /**
     * Admin approve: переводит заявку в APPROVED и soft-deletes CodeSet.
     * Если у CodeSet'а есть PUBLISHED-версии — нужен {@code forceArchive=true},
     * иначе {@link AdminCodeSetService#delete} бросит 409 (тот же guard, что в E18).
     *
     * <p>Идемпотентность: если CodeSet уже был soft-deleted параллельно (другой
     * admin удалил руками через /admin/codesets/{id}) — заявка всё равно
     * переводится в APPROVED, повторного soft-delete'а не делаем.
     */
    public void approve(UUID requestId, String decisionComment, boolean forceArchive, UUID admin) {
        jdbi.useTransaction(handle -> {
            AdminDeletionRequestDao dao = handle.attach(AdminDeletionRequestDao.class);
            BaseRow row = dao.findById(requestId)
                    .orElseThrow(() -> new IllegalArgumentException("Deletion request not found: " + requestId));
            if (!"PENDING".equals(row.status())) {
                throw new IllegalStateException("Cannot approve: current status is " + row.status());
            }
            if (row.requestedBy().equals(admin)) {
                throw new IllegalStateException(
                        "Self-approval is not allowed: requester and approver are the same user");
            }
            // Сначала soft-delete CodeSet'а — это бросает 409 если есть PUBLISHED без force_archive.
            // Если codeset уже deleted, AdminCodeSetService.delete вернёт IllegalArgumentException
            // ("not found or already deleted") — в нашем контексте это OK, продолжаем transition.
            try {
                codeSets.delete(row.codesetId(), forceArchive);
            } catch (IllegalArgumentException alreadyDeleted) {
                log.info("admin: deletion-request approve id={} — codeset {} already soft-deleted, proceeding",
                        requestId, row.codesetId());
            }
            int n = dao.transitionFromPending(requestId, "APPROVED", admin, decisionComment);
            if (n == 0) {
                // Race: кто-то параллельно (другой admin) уже decide'нул эту заявку.
                throw new IllegalStateException("Concurrent transition: request is no longer PENDING");
            }
            log.info("admin: deletion-request approve id={} codeset_id={} by={} force_archive={}",
                    requestId, row.codesetId(), admin, forceArchive);
        });
    }

    /**
     * Admin reject: переводит заявку в REJECTED с обязательным комментарием.
     * CodeSet остаётся живым.
     */
    public void reject(UUID requestId, String decisionComment, UUID admin) {
        String trimmed = decisionComment == null ? "" : decisionComment.trim();
        if (trimmed.length() < REASON_MIN || trimmed.length() > REASON_MAX) {
            throw new ValidationException(
                    "decision_comment length must be between "
                            + REASON_MIN + " and " + REASON_MAX + " characters for rejection");
        }
        jdbi.useTransaction(handle -> {
            AdminDeletionRequestDao dao = handle.attach(AdminDeletionRequestDao.class);
            BaseRow row = dao.findById(requestId)
                    .orElseThrow(() -> new IllegalArgumentException("Deletion request not found: " + requestId));
            if (!"PENDING".equals(row.status())) {
                throw new IllegalStateException("Cannot reject: current status is " + row.status());
            }
            if (row.requestedBy().equals(admin)) {
                throw new IllegalStateException(
                        "Self-rejection of own request is not allowed; use cancel instead");
            }
            int n = dao.transitionFromPending(requestId, "REJECTED", admin, trimmed);
            if (n == 0) {
                throw new IllegalStateException("Concurrent transition: request is no longer PENDING");
            }
            log.info("admin: deletion-request reject id={} codeset_id={} by={}",
                    requestId, row.codesetId(), admin);
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    public Optional<AdminDeletionRequestView> find(UUID id) {
        return jdbi.withExtension(AdminDeletionRequestDao.class, dao -> dao.findByIdDetailed(id))
                .map(AdminDeletionRequestService::toView);
    }

    private static AdminDeletionRequestView toView(DetailedRow r) {
        return new AdminDeletionRequestView(
                r.id().toString(),
                r.codesetId().toString(),
                r.codesetName(),
                r.domainId().toString(),
                r.domainName(),
                r.requestedBy().toString(),
                r.requestedByUsername(),
                r.reason(),
                r.status(),
                r.decidedBy() == null ? null : r.decidedBy().toString(),
                r.decidedByUsername(),
                r.decisionComment(),
                r.createdAt(),
                r.decidedAt(),
                r.codesetDeleted(),
                r.hasPublishedVersions());
    }

    private static boolean isUniqueViolation(UnableToExecuteStatementException e) {
        if (e.getCause() instanceof java.sql.SQLException sql) {
            return "23505".equals(sql.getSQLState());
        }
        return false;
    }

    /** 422 на стороне resource'а. */
    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    /** 403 на стороне resource'а — не свой запрос. */
    public static final class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }
}
