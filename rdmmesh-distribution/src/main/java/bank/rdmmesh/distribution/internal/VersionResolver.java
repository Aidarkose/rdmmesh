package bank.rdmmesh.distribution.internal;

import java.time.Instant;

/**
 * Парсит {@code version} query-параметр consumer-API. SPEC §3.5: значение — либо
 * {@code "published"} (default), либо явный semver. Pure-функция; БД-доступ — на
 * стороне сервиса, который сверяется с {@code DistributionDao}.
 */
public final class VersionResolver {

    private VersionResolver() {}

    /** {@code published} (буквальная строка) или semver-форма. */
    public sealed interface VersionSpec permits LatestPublished, Semver {}

    public static final class LatestPublished implements VersionSpec {
        public static final LatestPublished INSTANCE = new LatestPublished();
        private LatestPublished() {}
    }

    public record Semver(String value) implements VersionSpec {}

    public static VersionSpec parse(String raw) {
        if (raw == null || raw.isBlank() || "published".equalsIgnoreCase(raw.trim())) {
            return LatestPublished.INSTANCE;
        }
        String trimmed = raw.trim();
        if (!isSemverLike(trimmed)) {
            throw new IllegalArgumentException(
                    "version должна быть 'published' или semver, получено: " + raw);
        }
        return new Semver(trimmed);
    }

    /**
     * Минимальная форма semver: {@code MAJOR.MINOR.PATCH} с возможным pre-release-суффиксом
     * (например {@code 0.1.0-draft}). Полная регулярка по semver.org для нашего MVP избыточна —
     * authoring.SemVer и так нормализует то, что попадёт в БД, нам достаточно отсечь
     * откровенный мусор и SQL-injection попытки.
     */
    private static boolean isSemverLike(String s) {
        if (s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == '-' || c == '+' || c == '_';
            if (!ok) return false;
        }
        return s.contains(".");
    }

    public static Instant parseInstant(String iso, String paramName) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    paramName + " должен быть ISO-8601 instant (например 2026-05-06T12:00:00Z), получено: "
                            + iso);
        }
    }

    public static java.time.LocalDate parseDate(String iso, String paramName) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(iso.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    paramName + " должен быть ISO-8601 date (YYYY-MM-DD), получено: " + iso);
        }
    }
}
