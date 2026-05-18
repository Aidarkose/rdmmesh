package bank.rdmmesh.ownership.internal.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.port.SigningKeyPort;

class HmacVerifierTest {

    private static final byte[] KEY =
            "rdmmesh-test-hmac-key-must-be-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);

    private final HmacVerifier verifier = new HmacVerifier(() -> KEY.clone());

    @Test
    void roundtrip_sign_then_verify() {
        byte[] body = "{\"event_id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, KEY);
        assertThat(header).startsWith("sha256=");
        assertThat(verifier.verify(header, body)).isTrue();
    }

    @Test
    void verify_accepts_hex_without_prefix() {
        byte[] body = "ping".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, KEY);
        String hexOnly = header.substring("sha256=".length());
        assertThat(verifier.verify(hexOnly, body)).isTrue();
    }

    @Test
    void verify_rejects_tampered_body() {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, KEY);
        byte[] tampered = "{\"x\":2}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(header, tampered)).isFalse();
    }

    @Test
    void verify_rejects_wrong_key() {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] otherKey = "another-key-also-must-be-32-bytes-please".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, otherKey);
        assertThat(verifier.verify(header, body)).isFalse();
    }

    @Test
    void verify_rejects_null_or_blank_header() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(null, body)).isFalse();
        assertThat(verifier.verify("", body)).isFalse();
        assertThat(verifier.verify("not-hex", body)).isFalse();
    }

    @Test
    void verify_rejects_truncated_signature() {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, KEY);
        // Trim last hex char — длина теперь нечётная, parseHex падает.
        String truncated = header.substring(0, header.length() - 1);
        assertThat(verifier.verify(truncated, body)).isFalse();
    }

    // ── E14 round 6: rotation (primary + previous overlap) ──────────────────────

    private static final byte[] OLD_KEY =
            "rdmmesh-OLD-rotation-key-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_KEY =
            "rdmmesh-NEW-rotation-key-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8);

    /** acceptedHmacKeys = [NEW (primary), OLD (previous)] — overlap-окно ротации. */
    private final HmacVerifier rotating = new HmacVerifier(new SigningKeyPort() {
        @Override public byte[] currentHmacKey() { return NEW_KEY.clone(); }
        @Override public List<byte[]> acceptedHmacKeys() {
            return List.of(NEW_KEY.clone(), OLD_KEY.clone());
        }
    });

    @Test
    void rotation_accepts_signature_under_new_primary_key() {
        byte[] body = "{\"event_id\":\"evt-new\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(rotating.verify(HmacVerifier.sign(body, NEW_KEY), body)).isTrue();
    }

    @Test
    void rotation_still_accepts_signature_under_previous_key_during_overlap() {
        // OM ещё не переключился на новый секрет — подписывает старым ключом.
        byte[] body = "{\"event_id\":\"evt-old\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(rotating.verify(HmacVerifier.sign(body, OLD_KEY), body)).isTrue();
    }

    @Test
    void rotation_rejects_signature_under_unknown_key() {
        byte[] body = "{\"event_id\":\"evt-bad\"}".getBytes(StandardCharsets.UTF_8);
        byte[] strangerKey =
                "rdmmesh-STRANGER-not-in-accepted-set-32-bytes".getBytes(StandardCharsets.UTF_8);
        assertThat(rotating.verify(HmacVerifier.sign(body, strangerKey), body)).isFalse();
    }

    @Test
    void default_single_key_port_still_works_after_round6() {
        // Лямбда-порт (без override acceptedHmacKeys) — backward-compatible.
        byte[] body = "legacy".getBytes(StandardCharsets.UTF_8);
        HmacVerifier singleKey = new HmacVerifier(() -> KEY.clone());
        assertThat(singleKey.verify(HmacVerifier.sign(body, KEY), body)).isTrue();
        assertThat(singleKey.verify(HmacVerifier.sign(body, OLD_KEY), body)).isFalse();
    }
}
