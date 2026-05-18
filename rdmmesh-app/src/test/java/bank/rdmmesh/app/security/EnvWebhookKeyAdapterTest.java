package bank.rdmmesh.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class EnvWebhookKeyAdapterTest {

    @Test
    void rejects_blank_secret_id() {
        var adapter = EnvWebhookKeyAdapter.withDevFallback(
                "rdmmesh-dev-webhook-key-must-be-32-bytes-or-more");
        assertThatThrownBy(() -> adapter.resolveKey(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.resolveKey(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.resolveKey("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uses_dev_fallback_when_no_env() {
        // В окружении тестов env-vars не выставлены — резолвится в fallback.
        var adapter = EnvWebhookKeyAdapter.withDevFallback(
                "rdmmesh-dev-webhook-key-must-be-32-bytes-or-more");
        byte[] key = adapter.resolveKey("any-secret-id");
        assertThat(key)
                .hasSize("rdmmesh-dev-webhook-key-must-be-32-bytes-or-more".getBytes(StandardCharsets.UTF_8).length)
                .startsWith("rdmmesh-dev".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejects_short_dev_fallback() {
        // Защитный инвариант: ключ <32 байт обходом тоже нельзя.
        assertThatThrownBy(() -> EnvWebhookKeyAdapter.withDevFallback("short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void without_fallback_throws_when_no_env() {
        var adapter = new EnvWebhookKeyAdapter(null);
        assertThatThrownBy(() -> adapter.resolveKey("missing-secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── E14 round 6: resolveAllKeys (rotation overlap) ──────────────────────────

    @Test
    void resolve_all_keys_is_single_primary_without_previous_env() {
        // RDM_WEBHOOK_KEY_*_PREVIOUS в тест-окружении не задан → только primary (=fallback).
        var adapter = EnvWebhookKeyAdapter.withDevFallback(
                "rdmmesh-dev-webhook-key-must-be-32-bytes-or-more");
        var keys = adapter.resolveAllKeys("primary");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).isEqualTo(adapter.resolveKey("primary"));
    }

    @Test
    void resolve_all_keys_rejects_blank_secret_id() {
        var adapter = EnvWebhookKeyAdapter.withDevFallback(
                "rdmmesh-dev-webhook-key-must-be-32-bytes-or-more");
        assertThatThrownBy(() -> adapter.resolveAllKeys(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
