package bank.rdmmesh.catalog;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.CatalogMirrorPort;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.catalog.internal.CatalogMirrorAdapter;
import bank.rdmmesh.catalog.internal.CatalogReadAdapter;
import bank.rdmmesh.catalog.internal.service.CatalogService;
import bank.rdmmesh.catalog.resource.CodeSetResource;
import bank.rdmmesh.catalog.resource.CodeSetSchemaResource;
import bank.rdmmesh.catalog.resource.DomainResource;

/**
 * Composition factory модуля {@code rdmmesh-catalog}. Возвращает контейнер с уже
 * сконфигурированными resource'ами, чтобы {@code RdmmeshApplication} мог
 * зарегистрировать их одним блоком.
 */
public final class CatalogModule {

    private CatalogModule() {}

    public static Resources build(Jdbi jdbi, OwnershipPort ownershipPort) {
        CatalogService service = new CatalogService(jdbi, ownershipPort);
        return new Resources(
                new DomainResource(service),
                new CodeSetResource(service),
                new CodeSetSchemaResource(service));
    }

    /**
     * Read-only порт catalog'а для соседних модулей (authoring, publishing, distribution).
     * Не пересекается с {@link #build(Jdbi, OwnershipPort)} — те — write/HTTP, этот —
     * service-to-service интерфейс.
     */
    public static CatalogReadPort buildReadPort(Jdbi jdbi) {
        return new CatalogReadAdapter(jdbi);
    }

    /**
     * Mirror-port для OM ownership webhook'а (E7): UPSERT/soft-delete domain'ов и lookup
     * CodeSet'а по FQN. Не пересекается с {@link #buildReadPort(Jdbi)} — у того другая
     * семантика (read для соседних bounded contexts), хотя оба читают одни и те же таблицы.
     */
    public static CatalogMirrorPort buildMirrorPort(Jdbi jdbi) {
        return new CatalogMirrorAdapter(jdbi);
    }

    public record Resources(
            DomainResource domains,
            CodeSetResource codeSets,
            CodeSetSchemaResource schemas) {}
}
