package bank.rdmmesh.publishing.internal.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WebhookHmacTest {

    private static final byte[] KEY = "rdmmesh-test-webhook-key-must-be-long-enough-32"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void signature_is_64_lowercase_hex() {
        String hex = WebhookHmac.hexSignature("payload".getBytes(StandardCharsets.UTF_8), KEY);
        assertThat(hex).hasSize(64).matches("^[a-f0-9]{64}$");
    }

    @Test
    void verify_roundtrip() {
        byte[] body = "{\"event_id\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        String hex = WebhookHmac.hexSignature(body, KEY);
        String header = WebhookHmac.headerValue(hex);
        assertThat(WebhookHmac.verify(body, KEY, header)).isTrue();
    }

    @Test
    void verify_fails_on_tampered_body() {
        byte[] body = "{\"event_id\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "{\"event_id\":\"xyz\"}".getBytes(StandardCharsets.UTF_8);
        String header = WebhookHmac.headerValue(WebhookHmac.hexSignature(body, KEY));
        assertThat(WebhookHmac.verify(tampered, KEY, header)).isFalse();
    }

    @Test
    void verify_fails_on_wrong_key() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] wrongKey = "wrong-key-still-must-be-32-bytes-long-yes".getBytes(StandardCharsets.UTF_8);
        String header = WebhookHmac.headerValue(WebhookHmac.hexSignature(body, KEY));
        assertThat(WebhookHmac.verify(body, wrongKey, header)).isFalse();
    }

    @Test
    void verify_supports_header_with_or_without_prefix() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        String hex = WebhookHmac.hexSignature(body, KEY);
        assertThat(WebhookHmac.verify(body, KEY, hex)).isTrue();
        assertThat(WebhookHmac.verify(body, KEY, "sha256=" + hex)).isTrue();
        assertThat(WebhookHmac.verify(body, KEY, "  sha256=" + hex + "  ")).isTrue();
    }

    @Test
    void verify_rejects_malformed_header() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookHmac.verify(body, KEY, null)).isFalse();
        assertThat(WebhookHmac.verify(body, KEY, "")).isFalse();
        assertThat(WebhookHmac.verify(body, KEY, "sha256=zzzz")).isFalse();
    }
}
