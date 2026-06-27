package bank.rdmmesh.identity.internal.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import bank.rdmmesh.identity.internal.jwt.JwtValidator.InvalidJwtException;

/**
 * Покрывает контракт {@link JwtValidator}: signature/iss/aud/exp/required-claims.
 * Генерируем RSA-ключ один раз на класс, подписываем тестовые токены приватным,
 * валидатор получает паблик через {@link JwksKeyResolver} с in-memory {@link JwkProvider}.
 */
final class JwtValidatorTest {

    private static final String ISSUER = "http://kc.local/realms/bank";
    private static final String AUDIENCE = "rdmmesh-backend";
    private static final String KID = "test-kid-1";
    private static final List<String> REQUIRED = List.of("preferred_username", "sub");

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;
    private static JwtValidator validator;

    @BeforeAll
    static void initKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();

        JwkProvider provider = kid -> {
            if (!KID.equals(kid)) {
                throw new JwkException("Unknown kid: " + kid);
            }
            return new StaticJwkImpl(publicKey);
        };
        JwksKeyResolver resolver = new JwksKeyResolver(provider, Duration.ofMinutes(10));
        validator = new JwtValidator(
                resolver, ISSUER, AUDIENCE, REQUIRED, Duration.ofSeconds(60));
    }

    @Test
    void validates_a_well_formed_token() {
        UUID oid = UUID.randomUUID();
        String token = sign(b -> b
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(UUID.randomUUID().toString())
                .withClaim("preferred_username", "dev-steward")
                .withClaim("oid", oid.toString())
                .withClaim("groups", List.of("RDM_STEWARD")));

        var resolved = validator.validate(token);

        assertThat(resolved.preferredUsername()).isEqualTo("dev-steward");
        assertThat(resolved.objectGuid()).isEqualTo(oid);
        assertThat(resolved.groups()).containsExactly("RDM_STEWARD");
    }

    @Test
    void rejects_token_without_oid_claim() {
        // Валидный по подписи/iss/aud токен, но без AD objectGUID (claim `oid`) — отвергаем:
        // objectGUID — обязательный якорь идентичности.
        String token = sign(b -> b
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(UUID.randomUUID().toString())
                .withClaim("preferred_username", "x"));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("oid");
    }

    @Test
    void rejects_wrong_issuer() {
        String token = sign(b -> b
                .withIssuer("http://other/realms/foo")
                .withAudience(AUDIENCE)
                .withSubject(UUID.randomUUID().toString())
                .withClaim("preferred_username", "x"));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("verification failed");
    }

    @Test
    void rejects_wrong_audience() {
        String token = sign(b -> b
                .withIssuer(ISSUER)
                .withAudience("not-rdmmesh")
                .withSubject(UUID.randomUUID().toString())
                .withClaim("preferred_username", "x"));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejects_expired_token() {
        Date issuedAt = Date.from(Instant.now().minusSeconds(7200));
        Date expiresAt = Date.from(Instant.now().minusSeconds(3600));
        String token = sign(b -> b
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(UUID.randomUUID().toString())
                .withClaim("preferred_username", "x")
                .withIssuedAt(issuedAt)
                .withExpiresAt(expiresAt));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejects_missing_required_claim() {
        // sub есть (UUID), preferred_username отсутствует — проверим что валидатор словит.
        String token = sign(b -> b
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("preferred_username");
    }

    @Test
    void rejects_token_with_unknown_kid() {
        String token = JWT.create()
                .withKeyId("UNKNOWN_KID")
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(UUID.randomUUID().toString())
                .withClaim("preferred_username", "x")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.RSA256(publicKey, privateKey));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("Cannot resolve JWK");
    }

    @Test
    void rejects_token_with_no_kid_header() {
        String token = JWT.create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(UUID.randomUUID().toString())
                .withClaim("preferred_username", "x")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.RSA256(publicKey, privateKey));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("kid");
    }

    @Test
    void rejects_subject_that_is_not_uuid() {
        String token = sign(b -> b
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject("not-a-uuid")
                .withClaim("preferred_username", "x"));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("not a UUID");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static String sign(java.util.function.UnaryOperator<com.auth0.jwt.JWTCreator.Builder> apply) {
        var builder = JWT.create()
                .withKeyId(KID)
                .withIssuedAt(Date.from(Instant.now()))
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)));
        return apply.apply(builder).sign(Algorithm.RSA256(publicKey, privateKey));
    }

    /** Тестовый Jwk — Auth0 Jwk абстрактный, поэтому extend, не implement. */
    static final class StaticJwkImpl extends Jwk {
        private final PublicKey publicKey;

        StaticJwkImpl(PublicKey publicKey) {
            // Однозначный конструктор: id, type, alg, usage, operations:List<String>,
            // x5u, x5c:List<String>, x5t, additionalAttributes:Map<String,Object>.
            super(
                    KID,
                    "RSA",
                    "RS256",
                    null,
                    java.util.List.<String>of(),
                    null,
                    java.util.List.<String>of(),
                    null,
                    java.util.Map.<String, Object>of());
            this.publicKey = publicKey;
        }

        @Override
        public PublicKey getPublicKey() {
            return publicKey;
        }
    }
}
