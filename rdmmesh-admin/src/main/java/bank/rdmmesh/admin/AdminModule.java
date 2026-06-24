package bank.rdmmesh.admin;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.admin.internal.AdminCodeSetService;
import bank.rdmmesh.admin.internal.AdminDeletionRequestService;
import bank.rdmmesh.admin.internal.AdminDomainService;
import bank.rdmmesh.admin.internal.AdminOwnershipService;
import bank.rdmmesh.admin.internal.AdminTaskService;
import bank.rdmmesh.admin.resource.AdminCodeSetResource;
import bank.rdmmesh.admin.resource.AdminDeletionRequestResource;
import bank.rdmmesh.admin.resource.AdminDomainResource;
import bank.rdmmesh.admin.resource.AdminOwnershipResource;
import bank.rdmmesh.admin.resource.AdminTaskResource;
import bank.rdmmesh.admin.resource.AdminUserSearchResource;
import bank.rdmmesh.admin.resource.CodeSetDeletionRequestResource;
import bank.rdmmesh.admin.resource.DeletionRequestResource;

/**
 * Composition factory для rdmmesh-admin (E18, ADR-0011).
 *
 * <p>Модуль владеет schema {@code admin}, но также пишет в {@code catalog.domain},
 * {@code catalog.code_set}, {@code ownership.rdm_asset_ownership} и читает
 * {@code identity.rdm_user_mapping}. Эти cross-schema writes идут под
 * {@code @RolesAllowed("RDM_ADMIN")} и аудируются.
 */
public final class AdminModule {

    private AdminModule() {}

    public static Resources build(Jdbi jdbi) {
        AdminDomainService domains = new AdminDomainService(jdbi);
        AdminOwnershipService ownership = new AdminOwnershipService(jdbi);
        AdminCodeSetService codeSets = new AdminCodeSetService(jdbi);
        AdminTaskService tasks = new AdminTaskService(jdbi);
        AdminDeletionRequestService deletionRequests = new AdminDeletionRequestService(jdbi, codeSets);
        return new Resources(
                new AdminDomainResource(domains, ownership),
                new AdminOwnershipResource(ownership),
                new AdminCodeSetResource(codeSets, ownership),
                new AdminUserSearchResource(jdbi),
                new AdminTaskResource(tasks),
                new DeletionRequestResource(deletionRequests),
                new CodeSetDeletionRequestResource(deletionRequests),
                new AdminDeletionRequestResource(deletionRequests));
    }

    public record Resources(
            AdminDomainResource domains,
            AdminOwnershipResource ownership,
            AdminCodeSetResource codeSets,
            AdminUserSearchResource userSearch,
            AdminTaskResource tasks,
            DeletionRequestResource deletionRequests,
            CodeSetDeletionRequestResource codeSetDeletionRequests,
            AdminDeletionRequestResource adminDeletionRequests) {}
}
