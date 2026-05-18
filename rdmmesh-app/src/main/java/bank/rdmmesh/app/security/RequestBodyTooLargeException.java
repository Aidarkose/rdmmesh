package bank.rdmmesh.app.security;

/**
 * Сигнализирует, что фактически прочитанных байт тела запроса стало больше
 * {@link RequestSizeLimitFilter#MAX_BODY_BYTES}. Бросается из capped
 * input-stream'а (F3, chunked-without-Content-Length) и маппится в
 * {@code 413} через {@link RequestBodyTooLargeExceptionMapper}.
 *
 * <p>Unchecked — чтобы проходить сквозь JAX-RS MessageBodyReader (чтение
 * {@code byte[] rawBody} OM-webhook'а) и попадать в ExceptionMapper, а не
 * заворачиваться в невнятную 500.
 */
public final class RequestBodyTooLargeException extends RuntimeException {

    public RequestBodyTooLargeException(String message) {
        super(message);
    }
}
