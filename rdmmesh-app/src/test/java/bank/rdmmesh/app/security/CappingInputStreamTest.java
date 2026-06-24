package bank.rdmmesh.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * F3 (E14.8 §3 #3/#4): {@link CappingInputStream} рвёт чтение на
 * {@code maxBytes+1}-м байте независимо от {@code Content-Length} —
 * именно это закрывает chunked-without-length OOM-вектор.
 */
final class CappingInputStreamTest {

    private static InputStream capped(byte[] data, long max) {
        return new CappingInputStream(new ByteArrayInputStream(data), max);
    }

    @Test
    void readsUpToLimitInclusive() throws IOException {
        byte[] data = "12345678".getBytes(StandardCharsets.UTF_8); // 8 байт
        try (InputStream in = capped(data, 8)) {
            assertThat(in.readAllBytes()).containsExactly(data);
        }
    }

    @Test
    void byteWiseReadThrowsPastLimit() throws IOException {
        byte[] data = new byte[9];
        try (InputStream in = capped(data, 8)) {
            for (int i = 0; i < 8; i++) {
                assertThat(in.read()).isNotEqualTo(-1);
            }
            assertThatThrownBy(in::read)
                    .isInstanceOf(RequestBodyTooLargeException.class)
                    .hasMessageContaining("exceeds 8 bytes");
        }
    }

    @Test
    void bulkReadThrowsWhenOverflowing() {
        byte[] data = new byte[64 * 1024]; // имитируем chunked-поток без length
        assertThatThrownBy(() -> {
            try (InputStream in = capped(data, 1024)) {
                in.readAllBytes(); // буфер не успевает разрастись — рвётся на 1025-м
            }
        }).isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void emptyBodyIsFine() throws IOException {
        try (InputStream in = capped(new byte[0], 8)) {
            assertThat(in.read()).isEqualTo(-1);
        }
    }
}
