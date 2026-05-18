package bank.rdmmesh.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.distribution.internal.service.DistributionService;
import bank.rdmmesh.distribution.resource.RdmDistributionResource;

/**
 * Composition factory модуля {@code rdmmesh-distribution}. Возвращает один HTTP resource
 * (items / lookup / export). Ничего не пишет в БД — ArchUnit gates это проверяют.
 */
public final class DistributionModule {

    private DistributionModule() {}

    public static RdmDistributionResource buildResource(Jdbi jdbi, ObjectMapper json) {
        return new RdmDistributionResource(new DistributionService(jdbi, json));
    }
}
