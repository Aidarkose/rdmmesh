package bank.rdmmesh.identity.internal.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.SigningKeyNotFoundException;

import org.junit.jupiter.api.Test;

/**
 * E14 round 9 — авторитетная проверка F2 (E14.8 §1): negative-cache по
 * неизвестному {@code kid} не порождает повторный сетевой fetch в пределах
 * TTL. Раньше «no-refetch» был виден только косвенно по таймингу ручного
 * smoke'а (E14.8 §5 — честная оговорка).
 */
final class JwksNegativeCacheTest {

    /** Считает обращения к JWKS-источнику; всегда «kid не найден». */
    private static final class CountingProvider implements JwkProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Jwk get(String keyId) throws JwkException {
            calls.incrementAndGet();
            throw new SigningKeyNotFoundException("no jwk for " + keyId, null);
        }
    }

    @Test
    void unknownKidIsFetchedOnceThenNegativeCached() {
        CountingProvider delegate = new CountingProvider();
        JwksKeyResolver resolver = new JwksKeyResolver(delegate, Duration.ofMinutes(10));

        assertThatThrownBy(() -> resolver.getRsaKey("bogus"))
                .isInstanceOf(JwkException.class);
        // Повторные обращения по тому же неизвестному kid — без сетевого fetch'а.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resolver.getRsaKey("bogus"))
                    .isInstanceOf(JwkException.class);
        }

        assertThat(delegate.calls.get())
                .as("delegate.get вызван ровно один раз — остальное из negative-cache")
                .isEqualTo(1);
    }

    @Test
    void distinctUnknownKidsEachFetchedOnce() {
        CountingProvider delegate = new CountingProvider();
        JwksKeyResolver resolver = new JwksKeyResolver(delegate, Duration.ofMinutes(10));

        assertThatThrownBy(() -> resolver.getRsaKey("a")).isInstanceOf(JwkException.class);
        assertThatThrownBy(() -> resolver.getRsaKey("b")).isInstanceOf(JwkException.class);
        assertThatThrownBy(() -> resolver.getRsaKey("a")).isInstanceOf(JwkException.class);
        assertThatThrownBy(() -> resolver.getRsaKey("b")).isInstanceOf(JwkException.class);

        // По одному fetch'у на уникальный kid, повторы — из negative-cache.
        assertThat(delegate.calls.get()).isEqualTo(2);
    }
}
