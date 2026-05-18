package bank.rdmmesh.authoring.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.authoring.internal.diff.DiffCalculator;
import bank.rdmmesh.authoring.internal.service.AuthoringService;
import io.dropwizard.auth.Auth;

/**
 * {@code GET /versions/{versionId}/diff?from={fromVersionId}} — построковый diff
 * между двумя версиями одного и того же CodeSet'а. Любая аутентифицированная роль —
 * это read-операция; mutations здесь нет.
 *
 * <p>Контракт ответа совпадает с {@code rdmmesh-spec/schema/api/version-diff.json}, но
 * мы не используем сгенерированный {@code VersionDiff} POJO — там вложенный {@code DiffEntry}
 * с {@code Object before/after} ломается на сериализации. Здесь — собственные records.
 */
@Path("/versions/{versionId}/diff")
@Produces(MediaType.APPLICATION_JSON)
public final class VersionDiffResource {

    private final AuthoringService authoring;

    public VersionDiffResource(AuthoringService authoring) {
        this.authoring = authoring;
    }

    @GET
    public DiffResponse diff(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            @QueryParam("from") String fromVersionId) {
        if (fromVersionId == null || fromVersionId.isBlank()) {
            throw new WebApplicationException("'from' query parameter is required",
                    Response.Status.BAD_REQUEST);
        }
        UUID to = parseUuid(versionId, "versionId");
        UUID from = parseUuid(fromVersionId, "from");
        DiffCalculator.Result r;
        try {
            r = authoring.diff(to, from);
        } catch (IllegalArgumentException e) {
            // версии не нашлись или они из разных CodeSet'ов
            throw new NotFoundException(e.getMessage());
        }
        List<EntryDto> entries = new ArrayList<>(r.entries().size());
        for (DiffCalculator.Entry e : r.entries()) {
            entries.add(new EntryDto(e.op(), e.keyParts(), e.changedFields(), e.before(), e.after()));
        }
        return new DiffResponse(
                r.fromVersion(),
                r.toVersion(),
                new SummaryDto(
                        r.summary().added(),
                        r.summary().changed(),
                        r.summary().removed(),
                        r.summary().moved()),
                entries);
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(field + " must be a UUID",
                    Response.Status.BAD_REQUEST);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiffResponse(
            @JsonProperty("from_version") String fromVersion,
            @JsonProperty("to_version") String toVersion,
            @JsonProperty("summary") SummaryDto summary,
            @JsonProperty("entries") List<EntryDto> entries) {}

    public record SummaryDto(
            @JsonProperty("added") int added,
            @JsonProperty("changed") int changed,
            @JsonProperty("removed") int removed,
            @JsonProperty("moved") int moved) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntryDto(
            @JsonProperty("op") String op,
            @JsonProperty("key_parts") List<String> keyParts,
            @JsonProperty("changed_fields") List<String> changedFields,
            @JsonProperty("before") JsonNode before,
            @JsonProperty("after") JsonNode after) {}
}
