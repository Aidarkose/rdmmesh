package bank.rdmmesh.identity.internal.jwt;

import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Получает RSA-ключи из JWKS endpoint Keycloak с Caffeine-кэшированием. TTL — настраиваемый
 * (по дефолту 10 минут, см. handoff E2 §6). Concurrent get'ы по одному kid дедуплицируются
 * Caffeine'овской загрузкой.
 *
 * <p>На стороне приложения используется единственный экземпляр на инстанс сервиса (создаётся
 * в {@code IdentityModule}). Класс thread-safe.
 *
 * <p><b>Security hardening (E14 round 8, OWASP A05/A04).</b>
 *
 * <ul>
 *   <li><b>HTTP-таймауты на JWKS-fetch.</b> {@link UrlJwkProvider} строится с явными
 *       connect/read timeout'ами — без них медленный/висящий Keycloak держал бы
 *       Jersey-request-thread бесконечно (availability-DoS: JWT-валидация на пути
 *       КАЖДОГО запроса).
 *   <li><b>Negative-cache по неизвестному {@code kid}.</b> Валидация подписи идёт
 *       до любой авторизации, значит неаутентифицированный клиент, подставляя
 *       случайные {@code kid}, заставлял бы сервис на каждый запрос ходить в
 *       Keycloak за полным JWKS (amplification + thread-pressure). Промахи
 *       кэшируются на короткий TTL — повторный bogus-{@code kid} не порождает
 *       новый сетевой fetch.
 * </ul>
 */
public final class JwksKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyResolver.class);

    /** Таймауты JWKS-fetch (мс). Keycloak в том же кластере — секунды с запасом. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 3_000;
    /** Короткий TTL для negative-cache: реальная key-rotation подхватится быстро. */
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    private final JwkProvider delegate;
    private final Cache<String, RSAPublicKey> keyCache;
    private final Cache<String, Boolean> negativeCache;

    public JwksKeyResolver(URL jwksUrl, Duration ttl) {
        this(
                new UrlJwkProvider(
                        Objects.requireNonNull(jwksUrl, "jwksUrl"),
                        CONNECT_TIMEOUT_MS,
                        READ_TIMEOUT_MS),
                ttl);
    }

    /** Доступ для тестов: можно подменить {@link JwkProvider} на in-memory реализацию. */
    JwksKeyResolver(JwkProvider delegate, Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("TTL must be positive, got " + ttl);
        }
        this.keyCache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(64)
                .recordStats()
                .build();
        this.negativeCache = Caffeine.newBuilder()
                .expireAfterWrite(NEGATIVE_TTL)
                .maximumSize(256)
                .build();
    }

    /**
     * Возвращает RSA публичный ключ для указанного {@code kid}. Бросает {@link JwkException}
     * при недоступности JWKS endpoint или несовместимом типе ключа.
     */
    public RSAPublicKey getRsaKey(String kid) throws JwkException {
        var cached = keyCache.getIfPresent(kid);
        if (cached != null) {
            return cached;
        }
        // Negative-cache: bogus/unknown kid не должен порождать сетевой fetch на
        // КАЖДЫЙ запрос (pre-auth amplification). Реальная key-rotation подхватится
        // после NEGATIVE_TTL — приемлемая задержка для штатной ротации.
        if (negativeCache.getIfPresent(kid) != null) {
            throw new JwkException("Unknown kid=" + kid + " (negative-cached)");
        }
        try {
            Jwk jwk = delegate.get(kid);
            var publicKey = jwk.getPublicKey();
            if (!(publicKey instanceof RSAPublicKey rsa)) {
                throw new JwkException(
                        "JWK with kid=" + kid + " is not RSA: " + publicKey.getAlgorithm());
            }
            keyCache.put(kid, rsa);
            log.debug("Cached JWK kid={} (cache size={})", kid, keyCache.estimatedSize());
            return rsa;
        } catch (JwkException e) {
            negativeCache.put(kid, Boolean.TRUE);
            throw e;
        }
    }

    /** Вытесняет ключ — на случай явной key rotation. */
    public void invalidate(String kid) {
        keyCache.invalidate(kid);
    }

    /** Полная инвалидация — для оперативного обновления в incident response. */
    public void invalidateAll() {
        keyCache.invalidateAll();
    }
}
