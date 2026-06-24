package bank.rdmmesh.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * E14 round 6 — rotation-примитив {@code SigningKeyPort.acceptedHmacKeys()}.
 * Тесты конструкторов (env-var'ы в unit-окружении не выставлены, поэтому путь
 * {@code fromEnv} проверяется только через fallback — как в {@code EnvWebhookKeyAdapterTest}).
 */
class EnvSigningKeyAdapterTest {

    private static final byte[] PRIMARY =
            "rdmmesh-PRIMARY-hmac-key-at-least-32-bytes!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS =
            "rdmmesh-PREVIOUS-overlap-key-at-least-32-by".getBytes(StandardCharsets.UTF_8);

    @Test
    void single_key_constructor_is_backward_compatible() {
        var a = new EnvSigningKeyAdapter(PRIMARY);
        assertThat(a.currentHmacKey()).isEqualTo(PRIMARY);
        assertThat(a.acceptedHmacKeys()).containsExactly(PRIMARY);
    }

    @Test
    void primary_plus_previous_exposed_for_verify_primary_first() {
        var a = new EnvSigningKeyAdapter(PRIMARY, PREVIOUS);
        assertThat(a.currentHmacKey()).isEqualTo(PRIMARY);          // sign — всегда primary
        assertThat(a.acceptedHmacKeys()).containsExactly(PRIMARY, PREVIOUS); // verify — оба
    }

    @Test
    void null_previous_means_single_accepted_key() {
        var a = new EnvSigningKeyAdapter(PRIMARY, null);
        assertThat(a.acceptedHmacKeys()).containsExactly(PRIMARY);
    }

    @Test
    void rejects_short_primary() {
        assertThatThrownBy(() -> new EnvSigningKeyAdapter("short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnvSigningKeyAdapter(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_short_previous_fail_fast_on_misconfig() {
        assertThatThrownBy(() -> new EnvSigningKeyAdapter(
                PRIMARY, "tiny".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("_PREVIOUS");
    }

    @Test
    void keys_are_defensively_copied() {
        var a = new EnvSigningKeyAdapter(PRIMARY, PREVIOUS);
        a.currentHmacKey()[0] = 0;                 // мутация копии
        a.acceptedHmacKeys().get(0)[1] = 0;
        assertThat(a.currentHmacKey()).isEqualTo(PRIMARY);  // источник цел
    }

    @Test
    void from_env_uses_dev_fallback_without_previous_when_env_absent() {
        // RDM_HMAC_KEY / _PREVIOUS в тест-окружении не заданы → primary=fallback, previous отсутствует.
        String fallback = "rdmmesh-dev-fallback-key-at-least-32-bytes!!";
        var a = EnvSigningKeyAdapter.fromEnv("RDM_HMAC_KEY", fallback);
        assertThat(a.currentHmacKey()).isEqualTo(fallback.getBytes(StandardCharsets.UTF_8));
        assertThat(a.acceptedHmacKeys()).hasSize(1);
    }
}
