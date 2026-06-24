package bank.rdmmesh.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.RelationalReadPort;
import bank.rdmmesh.distribution.internal.service.DistributionService;
import bank.rdmmesh.distribution.resource.RdmDistributionResource;

/**
 * Composition factory модуля {@code rdmmesh-distribution}. Возвращает один HTTP resource
 * (items / lookup / export). Ничего не пишет в БД — ArchUnit gates это проверяют.
 *
 * <p>Stage 7b: items читаются из реляционного стора через {@link RelationalReadPort}.
 */
public final class DistributionModule {

    private DistributionModule() {}

    public static RdmDistributionResource buildResource(
            Jdbi jdbi, ObjectMapper json, RelationalReadPort relational) {
        return new RdmDistributionResource(new DistributionService(jdbi, json, relational));
    }
}
