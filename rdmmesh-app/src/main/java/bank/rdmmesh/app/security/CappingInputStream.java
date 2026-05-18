package bank.rdmmesh.app.security;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Считающий байты {@link InputStream}-декоратор: как только суммарно
 * прочитано больше {@code maxBytes}, бросает {@link
 * RequestBodyTooLargeException}. Чистый, без servlet-зависимостей — отсюда
 * unit-тестируется напрямую (F3, E14.8 §3 #3 / §3 #4).
 *
 * <p><b>Зачем поверх {@link RequestSizeLimitFilter}.</b> Тот фильтр
 * отбивает по заголовку {@code Content-Length} (быстрый путь штатных
 * клиентов). Запрос с {@code Transfer-Encoding: chunked} без
 * {@code Content-Length} его проходит — гарантию там даёт только
 * счётчик фактически прочитанных байт: heap не успевает вырасти, потому
 * что чтение прерывается на {@code maxBytes+1}-м байте <em>до</em>
 * полной буферизации (вектор: неаутентифицированный OM-webhook,
 * {@code byte[] rawBody}).
 */
final class CappingInputStream extends FilterInputStream {

    private final long maxBytes;
    private long count;

    CappingInputStream(InputStream in, long maxBytes) {
        super(in);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            tally(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            tally(n);
        }
        return n;
    }

    private void tally(long delta) {
        count += delta;
        if (count > maxBytes) {
            throw new RequestBodyTooLargeException(
                    "request body exceeds " + maxBytes + " bytes");
        }
    }
}
