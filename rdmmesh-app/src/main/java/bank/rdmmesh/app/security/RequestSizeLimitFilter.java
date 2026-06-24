package bank.rdmmesh.app.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Глобальный лимит размера тела запроса (E14 round 8, OWASP A04/A05 —
 * unrestricted resource consumption).
 *
 * <p><b>Зачем.</b> Единственный неаутентифицированный POST —
 * {@code /webhooks/om/ownership} — буферизует {@code byte[] rawBody} целиком в
 * heap <em>до</em> HMAC-проверки (HMAC обязан считаться по сырым байтам). Без
 * лимита неаутентифицированный клиент multi-GB-телом валит сервис по памяти.
 * Фильтр {@code @PreMatching} + наивысший приоритет → отрабатывает раньше
 * resource-матчинга, auth и чтения entity.
 *
 * <p><b>Граница применимости.</b> Проверяется заголовок {@code Content-Length}.
 * OM Event Subscription и обычные HTTP-клиенты его шлют. Для
 * chunked-without-length (нет {@code Content-Length}) этот фильтр гарантию не
 * даёт — это покрывается отдельным Jetty {@code httpConfiguration}-лимитом на
 * уровне коннектора (follow-up; см. handoff E14.8 §3).
 */
@Provider
@PreMatching
@Priority(1)
public final class RequestSizeLimitFilter implements ContainerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    /** 1 MiB. OM ChangeEvent — единицы КБ; leg-room ×100+. Общий лимит по умолчанию. */
    public static final long MAX_BODY_BYTES = 1L << 20;

    /**
     * 25 MiB — лимит для bulk-import эндпоинтов справочников ({@code /items/bulk*}).
     * Эти POST'ы аутентифицированы (Author/Admin) и принимают большие xlsx/csv с
     * десятками тысяч строк, поэтому им нужен потолок выше общего. Неаутентифицированный
     * вебхук под него не подпадает — остаётся на {@link #MAX_BODY_BYTES}.
     */
    public static final long MAX_IMPORT_BODY_BYTES = 25L << 20;

    /**
     * Лимит тела для пути запроса: bulk-import справочников допускает большие тела,
     * всё остальное — общий 1 MiB. Используется обоими фильтрами (JAX-RS и servlet),
     * чтобы порог считался единообразно.
     */
    public static long capForPath(String path) {
        if (path != null && path.contains("/items/bulk")) {
            return MAX_IMPORT_BODY_BYTES;
        }
        return MAX_BODY_BYTES;
    }

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String header = ctx.getHeaderString(HttpHeaders.CONTENT_LENGTH);
        if (header == null || header.isBlank()) {
            return;
        }
        long length;
        try {
            length = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            ctx.abortWith(problem(Response.Status.BAD_REQUEST, "invalid Content-Length"));
            return;
        }
        long cap = capForPath(ctx.getUriInfo().getPath());
        if (length > cap) {
            log.warn(
                    "request rejected: Content-Length={} > limit={} ({} {})",
                    length,
                    cap,
                    ctx.getMethod(),
                    ctx.getUriInfo().getPath());
            ctx.abortWith(problem(
                    Response.Status.REQUEST_ENTITY_TOO_LARGE,
                    "request body exceeds " + cap + " bytes"));
        }
    }

    private static Response problem(Response.Status status, String reason) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"error\":\"" + reason + "\"}")
                .build();
    }
}
