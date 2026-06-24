package bank.rdmmesh.authoring.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.authoring.internal.dao.CodeSetVersionDao;
import bank.rdmmesh.authoring.internal.dao.CodeSetVersionDao.VersionRow;

/**
 * Реализация {@link VersionLifecyclePort} над {@link CodeSetVersionDao}. Это единственный
 * write-путь в {@code authoring.code_set_version} извне модуля authoring; SPEC §3.3
 * требует, чтобы schemas {@code authoring} писал только модуль authoring — workflow при
 * transition'ах ходит сюда, а не лезет в DAO напрямую.
 */
public final class VersionLifecycleAdapter implements VersionLifecyclePort {

    private final Jdbi jdbi;

    public VersionLifecycleAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Optional<VersionSnapshot> findVersion(UUID versionId) {
        return jdbi.withExtension(CodeSetVersionDao.class, dao -> dao.findById(versionId))
                .map(VersionLifecycleAdapter::toSnapshot);
    }

    @Override
    public Set<UUID> reviewersOf(UUID versionId) {
        return new HashSet<>(jdbi.withExtension(CodeSetVersionDao.class,
                dao -> dao.reviewersOf(versionId)));
    }

    @Override
    public List<VersionSnapshot> openVersionsFor(UUID codesetId) {
        return jdbi.withExtension(CodeSetVersionDao.class,
                        dao -> dao.findOpenVersions(codesetId))
                .stream()
                .map(VersionLifecycleAdapter::toSnapshot)
                .toList();
    }

    @Override
    public boolean transition(
            UUID versionId,
            String fromStatus,
            String toStatus,
            UUID actor,
            TransitionEffect effect) {
        // Backward-compatible overload: открываем собственную tx и делегируем
        // на handle-вариант. Используется legacy-callers'ами и тестами; будущие
        // call-sites должны передавать свой Handle (E14 round 5).
        return jdbi.inTransaction(handle ->
                transition(handle, versionId, fromStatus, toStatus, actor, effect));
    }

    @Override
    public boolean transition(
            Handle handle,
            UUID versionId,
            String fromStatus,
            String toStatus,
            UUID actor,
            TransitionEffect effect) {
        // Используем чужой handle — caller сам открыл tx и контролирует commit.
        // Это нужно, чтобы WorkflowService мог объединить status-CAS + journal-INSERT
        // + approval-task UPSERT в одну Postgres tx (E14 round 5).
        CodeSetVersionDao dao = handle.attach(CodeSetVersionDao.class);
        int n = dao.casStatus(versionId, fromStatus, toStatus);
        if (n == 0) {
            return false;
        }
        if (effect.recordReviewer()) {
            dao.recordReviewer(versionId, actor);
        }
        if (effect.setApprover()) {
            dao.setApprover(versionId, actor);
        }
        return true;
    }

    @Override
    public boolean publish(UUID versionId, String contentHash, String signature, UUID publishedBy) {
        return jdbi.withExtension(CodeSetVersionDao.class,
                dao -> dao.markPublished(versionId, contentHash, signature, publishedBy)) > 0;
    }

    @Override
    public boolean publish(Handle handle, UUID versionId, String contentHash, String signature, UUID publishedBy) {
        // E14 round 5.1: работа на чужом handle — PublishingService объединяет
        // publish+journal+outbox в одну Postgres tx.
        return handle.attach(CodeSetVersionDao.class)
                .markPublished(versionId, contentHash, signature, publishedBy) > 0;
    }

    @Override
    public boolean deprecate(UUID versionId) {
        return jdbi.withExtension(CodeSetVersionDao.class,
                dao -> dao.markDeprecated(versionId)) > 0;
    }

    @Override
    public boolean deprecate(Handle handle, UUID versionId) {
        return handle.attach(CodeSetVersionDao.class).markDeprecated(versionId) > 0;
    }

    @Override
    public Optional<VersionSnapshot> findLatestPublished(UUID codesetId) {
        return jdbi.withExtension(CodeSetVersionDao.class,
                        dao -> dao.findLatestPublished(codesetId))
                .map(VersionLifecycleAdapter::toSnapshot);
    }

    @Override
    public Optional<String> findStoredContentHash(UUID versionId) {
        return jdbi.withExtension(CodeSetVersionDao.class,
                        dao -> dao.findById(versionId))
                .map(CodeSetVersionDao.VersionRow::contentHash);
    }

    @Override
    public Optional<PublishedVersionDetails> findPublishedDetails(UUID versionId) {
        return jdbi.withExtension(CodeSetVersionDao.class, dao -> dao.findById(versionId))
                .filter(row -> "PUBLISHED".equals(row.status()) || "DEPRECATED".equals(row.status()))
                .map(VersionLifecycleAdapter::toPublishedDetails);
    }

    @Override
    public Optional<PublishedVersionDetails> findPublishedDetails(Handle handle, UUID versionId) {
        // E14 round 5.1: read через shared handle — видит uncommitted writes
        // в той же tx (например, только что записанный publish'ем content_hash).
        return handle.attach(CodeSetVersionDao.class).findById(versionId)
                .filter(row -> "PUBLISHED".equals(row.status()) || "DEPRECATED".equals(row.status()))
                .map(VersionLifecycleAdapter::toPublishedDetails);
    }

    private static PublishedVersionDetails toPublishedDetails(CodeSetVersionDao.VersionRow row) {
        return new PublishedVersionDetails(
                row.id(),
                row.codesetId(),
                row.version(),
                row.status(),
                row.contentHash(),
                row.approvalSignature(),
                row.publishedBy(),
                row.publishedAt(),
                row.itemCount());
    }

    private static VersionSnapshot toSnapshot(VersionRow row) {
        return new VersionSnapshot(
                row.id(),
                row.codesetId(),
                row.version(),
                row.status(),
                row.createdBy());
    }
}
