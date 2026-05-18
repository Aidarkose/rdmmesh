package bank.rdmmesh.app.security;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Маппит {@link RequestBodyTooLargeException} (брошенный
 * {@link CappingInputStream} при chunked-overflow) в {@code 413} с тем же
 * JSON-телом {@code {"error": …}}, что отдаёт {@link RequestSizeLimitFilter}
 * на Content-Length-пути — единый контракт ошибки для обоих путей (F3).
 */
@Provider
public final class RequestBodyTooLargeExceptionMapper
        implements ExceptionMapper<RequestBodyTooLargeException> {

    @Override
    public Response toResponse(RequestBodyTooLargeException e) {
        return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"error\":\"" + e.getMessage() + "\"}")
                .build();
    }
}
