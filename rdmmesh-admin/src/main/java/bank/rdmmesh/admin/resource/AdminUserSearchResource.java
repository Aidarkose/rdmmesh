package bank.rdmmesh.admin.resource;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.admin.dto.AdminUserView;
import bank.rdmmesh.admin.internal.dao.AdminUserSearchDao;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * GET /api/v1/admin/users/search?q=...
 *
 * <p>Поиск в локальном mapping'е ({@code identity.rdm_user_mapping}). OM-source
 * (синхронный lookup в OM REST API) — задача E18.3 после подтверждения OM URL/токена.
 * До этого возвращаем только локально известных пользователей; если пользователь
 * никогда не логинился — admin его не увидит.
 */
@Path("/admin/users/search")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class AdminUserSearchResource {

    private static final int MAX_LIMIT = 50;

    private final Jdbi jdbi;

    public AdminUserSearchResource(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @GET
    public List<AdminUserView> search(
            @Auth RdmmeshPrincipal principal,
            @QueryParam("q") String q,
            @QueryParam("limit") Integer limit) {
        String query = (q == null) ? "" : q.trim();
        // Минимум 1 символ, иначе вернём пустой массив (LIKE '%%' выдал бы всех).
        if (query.isEmpty()) return List.of();
        int lim = (limit == null || limit < 1 || limit > MAX_LIMIT) ? 20 : limit;
        String pattern = "%" + query + "%";

        return jdbi.withExtension(AdminUserSearchDao.class, dao ->
                dao.search(pattern, lim).stream()
                        .map(r -> new AdminUserView(
                                r.omUserId().toString(),
                                r.username(),
                                r.displayName(),
                                r.email()))
                        .toList());
    }
}
