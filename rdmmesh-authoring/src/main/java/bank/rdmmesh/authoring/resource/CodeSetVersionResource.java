package bank.rdmmesh.authoring.resource;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.authoring.internal.service.AuthoringService;
import bank.rdmmesh.spec.entity.CodeSetVersion;
import io.dropwizard.auth.Auth;

/**
 * REST для авторинга — управление CodeSetVersion'ами:
 * <ul>
 *   <li>{@code GET  /versions/by-codeset/{codesetId}} — список версий CodeSet'а</li>
 *   <li>{@code POST /versions/by-codeset/{codesetId}} — создать draft (опционально из последней published)</li>
 *   <li>{@code GET  /versions/{id}} — одна версия</li>
 *   <li>{@code DELETE /versions/{id}} — удалить DRAFT (только в DRAFT)</li>
 * </ul>
 *
 * <p><b>Note по URL-форме.</b> SPEC §3.5 описывает {@code POST /codesets/{id}/versions},
 * но в Jersey два root-resource'а ({@code CodeSetResource @Path("/codesets")} и здесь)
 * с пересекающимися путями ведут к 404 на parametrized-сегменте — та же причина, по
 * которой E3 свернул {@code /domains/{id}/codesets} в {@code /codesets/by-domain/{id}}.
 * Поэтому здесь используем {@code /versions/by-codeset/{codesetId}}. Если в E11 (UI)
 * понадобится backwards-compat alias — добавится тонкий wrapper.
 *
 * <p>Transition'ы ({@code POST /versions/{id}/transitions}) — сфера E5 (Workflow).
 *
 * <p>Authorization: read — любой authenticated; create/delete draft — RDM_AUTHOR
 * либо RDM_ADMIN (последний — на случай восстановления процесса). Asset-level проверка
 * (Author данного домена) — после E7 (ownership webhook). Сейчас полагаемся на base role.
 */
@Path("/versions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class CodeSetVersionResource {

    private final AuthoringService authoring;

    public CodeSetVersionResource(AuthoringService authoring) {
        this.authoring = authoring;
    }

    @GET
    @Path("/by-codeset/{codesetId}")
    public List<CodeSetVersion> list(
            @Auth RdmmeshPrincipal principal,
            @PathParam("codesetId") String codesetId) {
        return authoring.listVersions(parseUuid(codesetId, "codesetId"));
    }

    @POST
    @Path("/by-codeset/{codesetId}")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response createDraft(
            @Auth RdmmeshPrincipal principal,
            @PathParam("codesetId") String codesetId,
            @Valid NewDraftRequest req) {
        UUID id = parseUuid(codesetId, "codesetId");
        try {
            CodeSetVersion created = authoring.createDraft(
                    id,
                    req == null ? null : req.version,
                    req == null ? null : req.bump,
                    req == null ? null : req.releaseChannel,
                    principal.omUserId());
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @GET
    @Path("/{versionId}")
    public CodeSetVersion get(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId) {
        UUID id = parseUuid(versionId, "versionId");
        return authoring.findVersion(id)
                .orElseThrow(() -> new NotFoundException("version " + versionId));
    }

    @DELETE
    @Path("/{versionId}")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response delete(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId) {
        boolean ok = authoring.deleteDraft(
                parseUuid(versionId, "versionId"), principal.omUserId());
        if (!ok) {
            throw new WebApplicationException(
                    "Version not deletable (must be DRAFT)", Response.Status.CONFLICT);
        }
        return Response.noContent().build();
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    public static final class NewDraftRequest {
        @JsonProperty("version")
        public String version;

        @JsonProperty("bump")
        public String bump;

        @JsonProperty("release_channel")
        public String releaseChannel;
    }
}
