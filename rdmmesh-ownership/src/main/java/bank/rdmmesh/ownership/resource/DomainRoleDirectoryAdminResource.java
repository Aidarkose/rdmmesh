package bank.rdmmesh.ownership.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonProperty;

import bank.rdmmesh.api.port.ApproverDirectoryPort;
import bank.rdmmesh.api.port.ApproverDirectoryPort.DirectoryEntry;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * {@code POST /admin/domain-role-directory/reload} (RDM_ADMIN, BR-22,
 * handoff E17) — полная замена справочника ролей домена: тело — снапшот,
 * применяется как {@code TRUNCATE + INSERT} одной транзакцией (см.
 * {@code PostgresApproverDirectoryPort.reload}). Источник снапшота сейчас —
 * локальный сид, позже — справочник, сгенерированный в OpenMetadata.
 */
@Path("/admin/domain-role-directory")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class DomainRoleDirectoryAdminResource {

    private final ApproverDirectoryPort directory;

    public DomainRoleDirectoryAdminResource(ApproverDirectoryPort directory) {
        this.directory = directory;
    }

    @POST
    @Path("/reload")
    @RolesAllowed("RDM_ADMIN")
    public Response reload(
            @Auth RdmmeshPrincipal principal,
            @NotNull ReloadRequest req) {
        List<DirectoryEntry> entries = new ArrayList<>();
        if (req.entries != null) {
            for (Entry e : req.entries) {
                entries.add(new DirectoryEntry(
                        parseUuid(e.omDomainId, "om_domain_id"),
                        e.role,
                        parseUuid(e.omUserId, "om_user_id"),
                        e.username,
                        e.displayName));
            }
        }
        int inserted = directory.reload(entries);
        return Response.ok(new ReloadResult(
                req.entries == null ? 0 : req.entries.size(), inserted)).build();
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException(
                    field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    /** Снапшот справочника — тело POST /admin/domain-role-directory/reload. */
    public static final class ReloadRequest {
        @JsonProperty("entries")
        public List<Entry> entries;
    }

    public static final class Entry {
        @JsonProperty("om_domain_id")
        public String omDomainId;

        @JsonProperty("role")
        public String role;

        @JsonProperty("om_user_id")
        public String omUserId;

        @JsonProperty("username")
        public String username;

        @JsonProperty("display_name")
        public String displayName;
    }

    public record ReloadResult(int received, int inserted) {}
}
