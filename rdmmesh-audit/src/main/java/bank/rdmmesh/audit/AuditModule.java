package bank.rdmmesh.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.ArchivePort;
import bank.rdmmesh.audit.internal.AuditArchiveService;
import bank.rdmmesh.audit.internal.AuditChainHasher;
import bank.rdmmesh.audit.internal.AuditChainVerifier;
import bank.rdmmesh.audit.internal.AuditService;
import bank.rdmmesh.audit.resource.AuditArchiveResource;
import bank.rdmmesh.audit.resource.AuditResource;

/**
 * Composition factory для {@code rdmmesh-audit}. Создаёт {@link AuditService} и
 * сразу подписывает его на {@link EventBus} — глобальная подписка на
 * {@link bank.rdmmesh.api.eventbus.DomainEvent}.
 *
 * <p>{@link Resources#resource()} — REST-endpoint admin-audit-viewer'а
 * (E11.2d, handoff E10 §3 #3) и verify-chain endpoint (E14 round 1).
 * Регистрируется в Jersey композиционным root'ом.
 */
public final class AuditModule {

    private AuditModule() {}

    public static Resources build(Jdbi jdbi, EventBus eventBus, ObjectMapper json) {
        AuditService service = new AuditService(jdbi, json);
        service.registerOn(eventBus);

        // Hasher для verify-стороны — собственный canonical-mapper, независимый
        // от global ObjectMapper'а (последний может быть сконфигурирован под
        // REST-needs и не давать byte-stable сериализацию).
        AuditChainHasher hasher = new AuditChainHasher(AuditChainHasher.defaultMapper());
        AuditChainVerifier verifier = new AuditChainVerifier(hasher);
        AuditResource resource = new AuditResource(jdbi, json, verifier, eventBus);
        return new Resources(service, resource);
    }

    /**
     * E14 round 10 — отдельная фабрика archive-resource'а. Не входит в
     * {@link #build}, чтобы не менять её сигнатуру (и не ломать IT-callers):
     * archive — опциональный ops-flow, зависит от {@link ArchivePort}
     * (disabled-by-default, если RDM_ARCHIVE_ENDPOINT пуст).
     */
    public static AuditArchiveResource buildArchiveResource(Jdbi jdbi, ArchivePort archive) {
        return new AuditArchiveResource(new AuditArchiveService(jdbi, archive));
    }

    /** Возвращаемый из {@link #build} composition root для wire-up'а в RdmmeshApplication. */
    public record Resources(AuditService service, AuditResource resource) {}
}
