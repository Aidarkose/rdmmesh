package bank.rdmmesh.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.authoring.internal.PublishedSnapshotAdapter;
import bank.rdmmesh.authoring.internal.VersionLifecycleAdapter;
import bank.rdmmesh.authoring.internal.service.AuthoringService;
import bank.rdmmesh.authoring.resource.ClosureAdminResource;
import bank.rdmmesh.authoring.resource.CodeItemResource;
import bank.rdmmesh.authoring.resource.CodeSetVersionResource;
import bank.rdmmesh.authoring.resource.VersionDiffResource;

/**
 * Composition factory для {@code rdmmesh-authoring}. Возвращает контейнер с тремя
 * resource'ами и одним service'ом — composition root приложения регистрирует resource'ы
 * в Jersey. Дополнительно экспортирует {@link VersionLifecyclePort} — write-side
 * контракт для E5 Workflow (см. SPEC §3.3 — schemas {@code authoring} пишет только
 * модуль authoring; workflow транзиции ходят сюда, а не в DAO напрямую).
 */
public final class AuthoringModule {

    private AuthoringModule() {}

    public static Resources build(
            Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json, EventBus eventBus) {
        AuthoringService service = new AuthoringService(jdbi, catalog, json, eventBus);
        return new Resources(
                service,
                new CodeSetVersionResource(service),
                new CodeItemResource(service),
                new VersionDiffResource(service),
                new ClosureAdminResource(service, eventBus));
    }

    /** Read+CAS-write порт authoring'а для модулей workflow / publishing. */
    public static VersionLifecyclePort buildLifecyclePort(Jdbi jdbi) {
        return new VersionLifecycleAdapter(jdbi);
    }

    /** Read-side порт canonical snapshot bytes для publishing (E6). */
    public static PublishedSnapshotPort buildSnapshotPort(Jdbi jdbi) {
        return new PublishedSnapshotAdapter(jdbi);
    }

    public record Resources(
            AuthoringService service,
            CodeSetVersionResource versions,
            CodeItemResource items,
            VersionDiffResource diff,
            ClosureAdminResource closureAdmin) {}
}
