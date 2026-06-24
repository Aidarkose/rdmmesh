package bank.rdmmesh.admin.resource;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonProperty;

import bank.rdmmesh.admin.dto.AdminDomainView;
import bank.rdmmesh.admin.dto.AdminOwnershipView;
import bank.rdmmesh.admin.internal.AdminDomainService;
import bank.rdmmesh.admin.internal.AdminOwnershipService;
import bank.rdmmesh.admin.resource.AdminOwnershipResource.AssignRequest;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * REST для admin-операций на catalog.domain (E18.2, ADR-0011).
 * Все методы требуют {@code RDM_ADMIN}.
 *
 * <p>Ownership list+assign для домена живут ЗДЕСЬ (а не в AdminOwnershipResource),
 * потому что JAX-RS root-resource matching выбирает наиболее специфичный класс-@Path:
 * для {@code /admin/domains/{id}/ownership} это {@code @Path("/admin/domains")},
 * и sub-resource метод должен быть в этом же классе (см. E17 прецедент).
 */
@Path("/admin/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class AdminDomainResource {

    private final AdminDomainService service;
    private final AdminOwnershipService ownership;

    public AdminDomainResource(AdminDomainService service, AdminOwnershipService ownership) {
        this.service = service;
        this.ownership = ownership;
    }

    @GET
    public List<AdminDomainView> list(@Auth RdmmeshPrincipal principal) {
        return service.list();
    }

    @GET
    @Path("/{id}")
    public AdminDomainView get(@Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        UUID uid = parseUuid(id, "id");
        return service.find(uid).orElseThrow(() -> new NotFoundException("domain " + id));
    }

    @POST
    public Response create(
            @Auth RdmmeshPrincipal principal,
            @Valid @NotNull CreateDomainRequest req) {
        UUID omId = req.omDomainId == null || req.omDomainId.isBlank()
                ? null : parseUuid(req.omDomainId, "om_domain_id");
        try {
            AdminDomainView created = service.create(new AdminDomainService.NewDomain(
                    omId, req.name, req.displayName, req.description,
                    req.labelRu, req.labelEn, req.tags));
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @PATCH
    @Path("/{id}")
    public AdminDomainView patch(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull PatchDomainRequest req) {
        UUID uid = parseUuid(id, "id");
        try {
            return service.patch(uid, new AdminDomainService.DomainPatch(
                            req.displayName, req.description,
                            req.labelRu, req.labelEn, req.tags))
                    .orElseThrow(() -> new NotFoundException("domain " + id));
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.FORBIDDEN);
        }
    }

    @POST
    @Path("/{id}:rename")
    public AdminDomainView rename(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull RenameRequest req) {
        UUID uid = parseUuid(id, "id");
        try {
            return service.rename(uid, req.newName);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.FORBIDDEN);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        UUID uid = parseUuid(id, "id");
        try {
            service.delete(uid);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @POST
    @Path("/{id}:link-to-om")
    public AdminDomainView linkToOm(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull LinkToOmRequest req) {
        UUID uid = parseUuid(id, "id");
        UUID omId = parseUuid(req.omDomainId, "om_domain_id");
        try {
            return service.linkToOm(uid, omId);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/{id}:unlink-from-om")
    public AdminDomainView unlinkFromOm(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id) {
        UUID uid = parseUuid(id, "id");
        try {
            return service.unlinkFromOm(uid);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    // ── Ownership домена (E18.4) — sub-resource методы этого класса ──────────────

    @GET
    @Path("/{id}/ownership")
    public List<AdminOwnershipView> listOwnership(
            @Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        UUID uid = parseUuid(id, "id");
        return ownership.listForAsset(uid, "DOMAIN");
    }

    @POST
    @Path("/{id}/ownership")
    public Response assignOwnership(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull AssignRequest req) {
        UUID uid = parseUuid(id, "id");
        UUID userId = parseUuid(req.omUserId, "om_user_id");
        try {
            AdminOwnershipView v = ownership.assign(
                    new AdminOwnershipService.NewAssignment(
                            uid, "DOMAIN", userId, req.role,
                            req.pinnedLocal != null && req.pinnedLocal),
                    principal.omUserId());
            return Response.status(Response.Status.CREATED).entity(v).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException(
                    field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    public static final class CreateDomainRequest {
        @JsonProperty("name")
        @NotEmpty
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$",
                message = "name must be lower snake_case, ≤64 chars")
        public String name;

        /** При указании → master='LINKED'; иначе → master='RDM'. */
        @JsonProperty("om_domain_id")
        public String omDomainId;

        @JsonProperty("display_name")
        public String displayName;

        @JsonProperty("description")
        public String description;

        @JsonProperty("label_ru")
        public String labelRu;

        @JsonProperty("label_en")
        public String labelEn;

        @JsonProperty("tags")
        public String[] tags;
    }

    public static final class PatchDomainRequest {
        @JsonProperty("display_name")
        public String displayName;

        @JsonProperty("description")
        public String description;

        @JsonProperty("label_ru")
        public String labelRu;

        @JsonProperty("label_en")
        public String labelEn;

        @JsonProperty("tags")
        public String[] tags;
    }

    public static final class RenameRequest {
        @JsonProperty("new_name")
        @NotEmpty
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$",
                message = "new_name must be lower snake_case, ≤64 chars")
        public String newName;
    }

    public static final class LinkToOmRequest {
        @JsonProperty("om_domain_id")
        @NotEmpty
        public String omDomainId;
    }
}
