package bank.rdmmesh.app;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * OIDC-настройки rdmmesh. Заполняются для realm'а, общего с OpenMetadata (см. SPEC §3.1).
 *
 * <p>Поле {@link #issuerUri} обязательно — оно используется и для валидации {@code iss},
 * и как "корень" для discovery (OIDC well-known эндпоинт). {@link #jwksUri} опциональный
 * (может быть выведен из issuer + standard suffix), но указывать явно безопаснее.
 */
public final class KeycloakConfig {

    @JsonProperty("issuerUri")
    @NotEmpty
    private String issuerUri = "http://localhost:8090/realms/bank";

    @JsonProperty("jwksUri")
    @NotEmpty
    private String jwksUri = "http://keycloak:8080/realms/bank/protocol/openid-connect/certs";

    /** Ожидаемый {@code aud} claim в access-токене. См. realm-bank.json (audience-mapper). */
    @JsonProperty("audience")
    @NotEmpty
    private String audience = "rdmmesh-backend";

    /** Имя claim'а, в котором приходит sAMAccountName (для маппинга на OM User.id). */
    @JsonProperty("usernameClaim")
    @NotEmpty
    private String usernameClaim = "preferred_username";

    /** Имя claim'а с массивом групп AD → базовых ролей RDM. */
    @JsonProperty("groupsClaim")
    @NotEmpty
    private String groupsClaim = "groups";

    /**
     * Дополнительные claim'ы, наличие которых обязательно (помимо {@code iss}/{@code aud}/
     * {@code exp}, проверяемых всегда). Например {@code [preferred_username, sub]}.
     */
    @JsonProperty("requiredClaims")
    @NotNull
    private List<String> requiredClaims = List.of("preferred_username", "sub", "oid");

    /**
     * TTL для JWKS-кэша (Caffeine). 10 минут — компромисс между rotation и нагрузкой. Тип —
     * {@link io.dropwizard.util.Duration} (а не {@link java.time.Duration}), потому что
     * Dropwizard YAML/Jackson по умолчанию парсит ISO-8601, а нам удобнее писать
     * {@code 10 minutes}/{@code 60 seconds} в config'е.
     */
    @JsonProperty("jwksCacheTtl")
    @NotNull
    private io.dropwizard.util.Duration jwksCacheTtl = io.dropwizard.util.Duration.minutes(10);

    /** Допустимое отклонение часов между сервисом и Keycloak при валидации {@code exp}/{@code nbf}. */
    @JsonProperty("clockSkew")
    @NotNull
    private io.dropwizard.util.Duration clockSkew = io.dropwizard.util.Duration.seconds(60);

    public String getIssuerUri() {
        return issuerUri;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public String getAudience() {
        return audience;
    }

    public String getUsernameClaim() {
        return usernameClaim;
    }

    public String getGroupsClaim() {
        return groupsClaim;
    }

    public List<String> getRequiredClaims() {
        return requiredClaims;
    }

    public Duration getJwksCacheTtl() {
        return Duration.ofNanos(jwksCacheTtl.toNanoseconds());
    }

    public Duration getClockSkew() {
        return Duration.ofNanos(clockSkew.toNanoseconds());
    }

    /**
     * Удобный wrapper для рантайма — возвращает Optional, чтобы JWKS uri можно было считать
     * выводимым из issuer'а в будущем (но сейчас всегда настроен явно).
     */
    public Optional<String> jwksUriOpt() {
        return Optional.ofNullable(jwksUri).filter(s -> !s.isBlank());
    }
}
