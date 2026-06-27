package bank.rdmmesh.identity.internal.jwt;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.auth0.jwk.JwkException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * Валидация Keycloak JWT (RS256). Проверяет:
 *
 * <ul>
 *   <li>signature (через {@link JwksKeyResolver});
 *   <li>{@code iss} == ожидаемый issuer;
 *   <li>{@code aud} содержит ожидаемую audience;
 *   <li>{@code exp}, {@code nbf} — с заданным clock-skew;
 *   <li>наличие всех имён из {@code requiredClaims}.
 * </ul>
 *
 * <p>Возвращает {@link Resolved} — компактное представление, которое потребляется выше уровнем
 * (port-реализация). Никаких внешних звонков (OM REST) этот класс не делает — это его контракт.
 */
public final class JwtValidator {

    private final JwksKeyResolver keyResolver;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final List<String> requiredClaims;
    private final Duration leeway;

    public JwtValidator(
            JwksKeyResolver keyResolver,
            String expectedIssuer,
            String expectedAudience,
            List<String> requiredClaims,
            Duration leeway) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer");
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience");
        this.requiredClaims = List.copyOf(requiredClaims);
        this.leeway = Objects.requireNonNull(leeway, "leeway");
    }

    public Resolved validate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new InvalidJwtException("Empty bearer token");
        }
        DecodedJWT decoded;
        try {
            decoded = JWT.decode(bearerToken);
        } catch (RuntimeException e) {
            throw new InvalidJwtException("Malformed JWT: " + e.getMessage(), e);
        }
        var kid = decoded.getKeyId();
        if (kid == null || kid.isBlank()) {
            throw new InvalidJwtException("JWT header is missing kid");
        }

        RSAPublicKey publicKey;
        try {
            publicKey = keyResolver.getRsaKey(kid);
        } catch (JwkException e) {
            throw new InvalidJwtException("Cannot resolve JWK for kid=" + kid, e);
        }

        var algorithm = Algorithm.RSA256(publicKey, /* private */ null);
        try {
            JWT.require(algorithm)
                    .withIssuer(expectedIssuer)
                    .withAudience(expectedAudience)
                    .acceptLeeway(leeway.getSeconds())
                    .build()
                    .verify(decoded);
        } catch (JWTVerificationException e) {
            throw new InvalidJwtException("JWT verification failed: " + e.getMessage(), e);
        }

        for (String required : requiredClaims) {
            Claim c = decoded.getClaim(required);
            if (c == null || c.isMissing() || c.isNull()) {
                throw new InvalidJwtException(
                        "Required claim '" + required + "' is missing from JWT");
            }
        }

        return Resolved.from(decoded);
    }

    /** Compact projection of a verified JWT — keeps the validator decoupled from the port. */
    public record Resolved(
            UUID subject,
            UUID objectGuid,
            String preferredUsername,
            String email,
            String displayName,
            Set<String> groups,
            DecodedJWT raw) {

        static Resolved from(DecodedJWT jwt) {
            UUID sub;
            try {
                sub = UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException ex) {
                throw new InvalidJwtException("sub claim is not a UUID: " + jwt.getSubject());
            }
            // AD objectGUID (claim `oid`) — стабильный якорь идентичности. В проде
            // приходит из LDAP attribute mapper'а Keycloak; в dev — из realm-атрибута.
            String oidRaw = stringClaim(jwt, "oid");
            UUID objectGuid;
            try {
                objectGuid = UUID.fromString(oidRaw);
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new InvalidJwtException("oid (objectGUID) claim missing or not a UUID: " + oidRaw);
            }
            String preferredUsername = stringClaim(jwt, "preferred_username");
            String email = stringClaim(jwt, "email");
            String displayName = stringClaim(jwt, "name");
            Set<String> groups = new HashSet<>();
            Claim groupsClaim = jwt.getClaim("groups");
            if (groupsClaim != null && !groupsClaim.isMissing() && !groupsClaim.isNull()) {
                List<String> asList = groupsClaim.asList(String.class);
                if (asList != null) {
                    groups.addAll(asList);
                }
            }
            return new Resolved(
                    sub, objectGuid, preferredUsername, email, displayName, Set.copyOf(groups), jwt);
        }

        private static String stringClaim(DecodedJWT jwt, String name) {
            Claim c = jwt.getClaim(name);
            if (c == null || c.isMissing() || c.isNull()) {
                return null;
            }
            return c.asString();
        }
    }

    /** Wrapped sentinel — не наследуется от RuntimeException Auth0, чтобы упростить ловлю выше. */
    public static final class InvalidJwtException extends RuntimeException {
        public InvalidJwtException(String message) {
            super(message);
        }

        public InvalidJwtException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
