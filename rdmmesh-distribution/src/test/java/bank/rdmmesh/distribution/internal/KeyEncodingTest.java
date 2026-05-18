package bank.rdmmesh.distribution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class KeyEncodingTest {

    @Test
    void decode_single_part_token() {
        String token = base64url("[\"S2\"]");
        assertThat(KeyEncoding.decode(token)).containsExactly("S2");
    }

    @Test
    void decode_composite_key() {
        String token = base64url("[\"RETAIL\",\"BB\",\"12M\"]");
        assertThat(KeyEncoding.decode(token)).containsExactly("RETAIL", "BB", "12M");
    }

    @Test
    void decode_rejects_empty() {
        assertThatThrownBy(() -> KeyEncoding.decode("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyEncoding.decode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_rejects_garbage_base64() {
        assertThatThrownBy(() -> KeyEncoding.decode("!@#$%^&*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_rejects_non_array_payload() {
        String token = base64url("{\"not\":\"array\"}");
        assertThatThrownBy(() -> KeyEncoding.decode(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toJsonArray_compacts() {
        // Без пробелов — пригодно для CAST в jsonb через text-bind.
        assertThat(KeyEncoding.toJsonArray(List.of("a", "b")))
                .isEqualTo("[\"a\",\"b\"]");
    }

    private static String base64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
