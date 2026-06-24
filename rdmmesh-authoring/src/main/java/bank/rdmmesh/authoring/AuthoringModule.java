package bank.rdmmesh.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.api.port.ReferenceIntegrityPort;
import bank.rdmmesh.api.port.RelationalReadPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.authoring.internal.PublishedSnapshotAdapter;
import bank.rdmmesh.authoring.internal.ReferenceIntegrityAdapter;
import bank.rdmmesh.authoring.internal.RelationalReadAdapter;
import bank.rdmmesh.authoring.internal.VersionLifecycleAdapter;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService;
import bank.rdmmesh.authoring.internal.service.AuthoringService;
import bank.rdmmesh.authoring.resource.CodeItemResource;
import bank.rdmmesh.authoring.resource.CodeSetVersionResource;
import bank.rdmmesh.authoring.resource.RelationalCodeSetResource;
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
        RelationalStoreService relationalStore = new RelationalStoreService(jdbi, catalog, json);
        // Stage 2-final: relational store пересобирает __current после publish'а (E6) —
        // подписка на VersionPublishedDomainEvent на том же in-process bus, что и publishing.
        if (eventBus != null) {
            relationalStore.registerOn(eventBus);
        }
        AuthoringService service =
                new AuthoringService(jdbi, catalog, json, eventBus, relationalStore);
        return new Resources(
                service,
                new CodeSetVersionResource(service),
                new CodeItemResource(service),
                new VersionDiffResource(service),
                new RelationalCodeSetResource(relationalStore));
    }

    /** Read+CAS-write порт authoring'а для модулей workflow / publishing. */
    public static VersionLifecyclePort buildLifecyclePort(Jdbi jdbi) {
        return new VersionLifecycleAdapter(jdbi);
    }

    /** Read-side порт canonical snapshot bytes для publishing (E6). Stage 7c: из rd_data. */
    public static PublishedSnapshotPort buildSnapshotPort(
            Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json) {
        return new PublishedSnapshotAdapter(new RelationalStoreService(jdbi, catalog, json));
    }

    /** Read-side порт реляционного стора (rd_data) для distribution (Stage 7b). */
    public static RelationalReadPort buildRelationalReadPort(
            Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json) {
        return new RelationalReadAdapter(new RelationalStoreService(jdbi, catalog, json));
    }

    /** Порт проверки ссылочной целостности для workflow submit-гейта (Stage 7). */
    public static ReferenceIntegrityPort buildReferenceIntegrityPort(
            Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json) {
        return new ReferenceIntegrityAdapter(new RelationalStoreService(jdbi, catalog, json));
    }

    public record Resources(
            AuthoringService service,
            CodeSetVersionResource versions,
            CodeItemResource items,
            VersionDiffResource diff,
            RelationalCodeSetResource relational) {}
}
