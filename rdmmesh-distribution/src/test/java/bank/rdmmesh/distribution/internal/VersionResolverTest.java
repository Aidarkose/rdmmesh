package bank.rdmmesh.distribution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class VersionResolverTest {

    @Test
    void parse_default_or_blank_returns_latest_published() {
        assertThat(VersionResolver.parse(null)).isInstanceOf(VersionResolver.LatestPublished.class);
        assertThat(VersionResolver.parse("")).isInstanceOf(VersionResolver.LatestPublished.class);
        assertThat(VersionResolver.parse("  ")).isInstanceOf(VersionResolver.LatestPublished.class);
        assertThat(VersionResolver.parse("published")).isInstanceOf(VersionResolver.LatestPublished.class);
        assertThat(VersionResolver.parse("PUBLISHED")).isInstanceOf(VersionResolver.LatestPublished.class);
    }

    @Test
    void parse_semver_keeps_value() {
        var v = (VersionResolver.Semver) VersionResolver.parse("0.1.0");
        assertThat(v.value()).isEqualTo("0.1.0");
    }

    @Test
    void parse_semver_with_pre_release() {
        var v = (VersionResolver.Semver) VersionResolver.parse("1.2.3-rc1");
        assertThat(v.value()).isEqualTo("1.2.3-rc1");
    }

    @Test
    void parse_rejects_garbage() {
        assertThatThrownBy(() -> VersionResolver.parse("' OR 1=1; --"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejects_too_long() {
        String tooLong = "1.2.3" + "-" + "a".repeat(100);
        assertThatThrownBy(() -> VersionResolver.parse(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_requires_dot_in_semver() {
        // Без точки — не semver-like даже если все символы валидные.
        assertThatThrownBy(() -> VersionResolver.parse("v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseInstant_blank_returns_null() {
        assertThat(VersionResolver.parseInstant(null, "x")).isNull();
        assertThat(VersionResolver.parseInstant("", "x")).isNull();
    }

    @Test
    void parseInstant_iso8601() {
        Instant t = VersionResolver.parseInstant("2026-05-06T12:00:00Z", "knowledge_as_of");
        assertThat(t).isNotNull();
    }

    @Test
    void parseInstant_invalid_throws() {
        assertThatThrownBy(() -> VersionResolver.parseInstant("yesterday", "knowledge_as_of"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge_as_of");
    }

    @Test
    void parseDate_iso() {
        LocalDate d = VersionResolver.parseDate("2026-05-06", "as_of");
        assertThat(d).isEqualTo(LocalDate.of(2026, 5, 6));
    }

    @Test
    void parseDate_invalid_throws() {
        assertThatThrownBy(() -> VersionResolver.parseDate("06/05/2026", "as_of"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("as_of");
    }
}
