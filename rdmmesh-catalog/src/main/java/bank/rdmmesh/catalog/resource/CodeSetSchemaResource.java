package bank.rdmmesh.catalog.resource;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.catalog.internal.mapper.CatalogMappers;
import bank.rdmmesh.catalog.internal.service.CatalogService;
import io.dropwizard.auth.Auth;

/**
 * REST для CodeSetSchema (SPEC §3.5). Active schema и история ревизий.
 *
 * <p>{@code PUT} активирует следующую ревизию (monotonic version++), это эффективно «major
 * bump» в терминах SPEC — структура attributes изменилась, новые drafts будут
 * валидироваться против неё.
 */
@Path("/codesets/{codesetId}/schema")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class CodeSetSchemaResource {

    private final CatalogService catalog;

    public CodeSetSchemaResource(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GET
    public CodeSetSchemaDto getActive(
            @Auth RdmmeshPrincipal principal,
            @PathParam("codesetId") String codesetId) {
        UUID uid = CatalogMappers.parseUuid(codesetId, "codesetId");
        return catalog.currentSchema(uid)
                .orElseThrow(() -> new NotFoundException("codeset " + codesetId + " has no schema"));
    }

    @GET
    @Path("/history")
    public List<CodeSetSchemaDto> history(
            @Auth RdmmeshPrincipal principal,
            @PathParam("codesetId") String codesetId) {
        UUID uid = CatalogMappers.parseUuid(codesetId, "codesetId");
        return catalog.schemaHistory(uid);
    }

    @PUT
    @RolesAllowed({"RDM_STEWARD", "RDM_ADMIN"})
    public CodeSetSchemaDto putRevision(
            @Auth RdmmeshPrincipal principal,
            @PathParam("codesetId") String codesetId,
            @Valid @NotNull SchemaRevisionRequest req) {
        UUID uid = CatalogMappers.parseUuid(codesetId, "codesetId");
        return catalog.putSchemaRevision(
                uid,
                CatalogMappers.readJsonNode(req.jsonSchema),
                principal.omUserId());
    }

    public static final class SchemaRevisionRequest {
        @JsonProperty("json_schema")
        @NotNull
        public JsonNode jsonSchema;
    }
}
