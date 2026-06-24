package bank.rdmmesh.app.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet-уровневый hard-cap размера тела запроса (F3, закрывает E14.8
 * §3 #3 — chunked-without-Content-Length). Дополняет
 * {@link RequestSizeLimitFilter} (JAX-RS, отбой по {@code Content-Length}):
 *
 * <ul>
 *   <li><b>fast-path.</b> Если {@code Content-Length} присутствует и больше
 *       лимита — {@code 413} сразу, до Jersey (defense-in-depth дубль
 *       JAX-RS-фильтра, но ещё раньше в цепочке);</li>
 *   <li><b>chunked-path.</b> Тело оборачивается {@link CappingInputStream}:
 *       при попытке прочитать больше лимита — {@link
 *       RequestBodyTooLargeException} → {@code 413} через
 *       {@link RequestBodyTooLargeExceptionMapper}. Работает <em>без</em>
 *       {@code Content-Length}, т.к. считает фактически прочитанные байты;
 *       heap не успевает вырасти.</li>
 * </ul>
 *
 * <p>Регистрируется на servlet-контексте раньше Jersey (см.
 * {@code RdmmeshApplication.run}), поэтому покрывает и
 * {@code /api/v1/webhooks/om/ownership} (единственный неаутентифицированный
 * POST с {@code byte[] rawBody}).
 */
public final class RequestBodyCapFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyCapFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {
        if (!(request instanceof HttpServletRequest http)
                || !(response instanceof HttpServletResponse httpResp)) {
            chain.doFilter(request, response);
            return;
        }

        long cap = RequestSizeLimitFilter.capForPath(http.getRequestURI());
        long declared = http.getContentLengthLong();
        if (declared > cap) {
            log.warn("request rejected (servlet-layer): Content-Length={} > limit={} ({} {})",
                    declared, cap, http.getMethod(), http.getRequestURI());
            httpResp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            httpResp.setContentType("application/json");
            httpResp.getWriter().write(
                    "{\"error\":\"request body exceeds " + cap + " bytes\"}");
            return;
        }

        chain.doFilter(new CappedBodyRequest(http, cap), response);
    }

    /** Подменяет тело на {@link CappingInputStream}-обёртку. */
    private static final class CappedBodyRequest extends HttpServletRequestWrapper {

        private final long cap;

        private CappedBodyRequest(HttpServletRequest request, long cap) {
            super(request);
            this.cap = cap;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream raw = super.getInputStream();
            CappingInputStream capped = new CappingInputStream(raw, cap);
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    return capped.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return capped.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return raw.isFinished();
                }

                @Override
                public boolean isReady() {
                    return raw.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    raw.setReadListener(listener);
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    getInputStream(), enc != null ? enc : "UTF-8"));
        }
    }
}
