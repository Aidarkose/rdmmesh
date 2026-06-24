package bank.rdmmesh.authoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class KeyEncodingTest {

    @Test
    void roundtrip_single_key() {
        String token = KeyEncoding.encode(List.of("KZ"));
        assertThat(KeyEncoding.decode(token)).containsExactly("KZ");
    }

    @Test
    void roundtrip_composite_key() {
        List<String> key = List.of("RETAIL", "BB", "12M");
        String token = KeyEncoding.encode(key);
        assertThat(KeyEncoding.decode(token)).containsExactlyElementsOf(key);
    }

    @Test
    void encodes_url_safe_characters_only() {
        String token = KeyEncoding.encode(List.of("a/b+c=d"));
        // base64url не использует '+', '/' и '=' (no-padding)
        assertThat(token).doesNotContain("+", "/", "=");
        assertThat(KeyEncoding.decode(token)).containsExactly("a/b+c=d");
    }

    @Test
    void rejects_garbage_token() {
        assertThatThrownBy(() -> KeyEncoding.decode("not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_empty_token() {
        assertThatThrownBy(() -> KeyEncoding.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
