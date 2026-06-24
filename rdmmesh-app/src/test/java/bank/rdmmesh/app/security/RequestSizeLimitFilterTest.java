package bank.rdmmesh.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E14 round 9 — авторитетная проверка F3 (E14.8 §1): глобальный лимит размера
 * тела отбивает oversize до auth/чтения entity. Раньше — только ручной smoke
 * ({@code curl} 2MiB → 413, E14.8 §5).
 */
final class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

    private ContainerRequestContext ctxWith(String contentLength) {
        return ctxWith(contentLength, "/webhooks/om/ownership");
    }

    private ContainerRequestContext ctxWith(String contentLength, String path) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getHeaderString(HttpHeaders.CONTENT_LENGTH)).thenReturn(contentLength);
        UriInfo uri = mock(UriInfo.class);
        lenient().when(uri.getPath()).thenReturn(path);
        lenient().when(ctx.getUriInfo()).thenReturn(uri);
        lenient().when(ctx.getMethod()).thenReturn("POST");
        return ctx;
    }

    @Test
    void oversizeBodyAbortedWith413() throws IOException {
        ContainerRequestContext ctx =
                ctxWith(Long.toString(RequestSizeLimitFilter.MAX_BODY_BYTES + 1));

        filter.filter(ctx);

        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertThat(resp.getValue().getStatus())
                .isEqualTo(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
    }

    @Test
    void normalBodyPassesThrough() throws IOException {
        ContainerRequestContext ctx = ctxWith("4096");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void missingContentLengthPassesThrough() throws IOException {
        ContainerRequestContext ctx = ctxWith(null);
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void invalidContentLengthAbortedWith400() throws IOException {
        ContainerRequestContext ctx = ctxWith("not-a-number");

        filter.filter(ctx);

        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertThat(resp.getValue().getStatus())
                .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void boundaryExactLimitPassesThrough() throws IOException {
        ContainerRequestContext ctx = ctxWith(Long.toString(RequestSizeLimitFilter.MAX_BODY_BYTES));
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void bulkImportPathAllowsLargerBody() throws IOException {
        // 3 MiB — больше общего 1 MiB, но в пределах import-лимита (25 MiB).
        ContainerRequestContext ctx =
                ctxWith(Long.toString(3L << 20), "versions/123/items/bulk-xlsx");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void bulkImportPathStillRejectsBeyondImportLimit() throws IOException {
        ContainerRequestContext ctx =
                ctxWith(
                        Long.toString(RequestSizeLimitFilter.MAX_IMPORT_BODY_BYTES + 1),
                        "versions/123/items/bulk-xlsx");

        filter.filter(ctx);

        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertThat(resp.getValue().getStatus())
                .isEqualTo(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
    }
}
