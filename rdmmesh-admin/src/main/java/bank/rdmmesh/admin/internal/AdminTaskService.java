package bank.rdmmesh.admin.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.admin.dto.AdminTaskView;
import bank.rdmmesh.admin.internal.dao.AdminTaskDao;
import bank.rdmmesh.admin.internal.dao.AdminTaskDao.TaskRow;

public final class AdminTaskService {

    private static final Logger log = LoggerFactory.getLogger(AdminTaskService.class);

    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "LINK", "CREATE_NEW", "MERGE", "IGNORE",
            "CONVERT_TO_RDM_LOCAL", "SOFT_DELETE",
            "CONFIRM_REMOVAL", "KEEP_LOCAL_PIN", "SWITCH_ORIGIN_TO_OM");

    private final Jdbi jdbi;

    public AdminTaskService(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public List<AdminTaskView> listPending() {
        return jdbi.withExtension(AdminTaskDao.class, dao ->
                dao.findPending().stream().map(AdminTaskService::toView).toList());
    }

    public void resolve(UUID id, String action, String notes, UUID actor) {
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                    "action must be one of " + ALLOWED_ACTIONS + ", got " + action);
        }
        jdbi.useTransaction(handle -> {
            int n = handle.attach(AdminTaskDao.class).resolve(id, action, actor, notes);
            if (n == 0) {
                throw new IllegalArgumentException(
                        "Task not found or already resolved: " + id);
            }
            log.info("admin: resolved task id={} action={} actor={}", id, action, actor);
            // TODO E18.3: side-effect'ы (например, при action=LINK провести UPDATE
            // catalog.domain master='LINKED', om_domain_id=...). Сейчас задач нет
            // вовсе (webhook receiver их не генерит до E18.3), поэтому пустая
            // реализация — это валидный no-op.
        });
    }

    private static AdminTaskView toView(TaskRow r) {
        return new AdminTaskView(
                r.id().toString(),
                r.taskType(),
                r.sourceEventId(),
                r.relatedDomainId() == null ? null : r.relatedDomainId().toString(),
                r.payloadJson(),
                r.status(),
                r.createdAt());
    }
}
