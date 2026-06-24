package bank.rdmmesh.authoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SemVerTest {

    @Test
    void parses_clean_versions() {
        SemVer v = SemVer.parse("1.2.3");
        assertThat(v.major).isEqualTo(1);
        assertThat(v.minor).isEqualTo(2);
        assertThat(v.patch).isEqualTo(3);
        assertThat(v.preRelease).isNull();
    }

    @Test
    void parses_pre_release_suffix() {
        SemVer v = SemVer.parse("1.0.0-draft");
        assertThat(v.preRelease).isEqualTo("draft");
        assertThat(v.render()).isEqualTo("1.0.0-draft");
    }

    @Test
    void rejects_garbage() {
        assertThat(SemVer.isValid("v1.2.3")).isFalse();
        assertThat(SemVer.isValid("1.2")).isFalse();
        assertThat(SemVer.isValid("01.2.3")).isFalse(); // leading zero
        assertThatThrownBy(() -> SemVer.parse("not-semver"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bumps_each_axis() {
        SemVer v = SemVer.parse("1.2.3");
        assertThat(v.bumpMajor().render()).isEqualTo("2.0.0");
        assertThat(v.bumpMinor().render()).isEqualTo("1.3.0");
        assertThat(v.bumpPatch().render()).isEqualTo("1.2.4");
    }

    @Test
    void next_for_no_previous_uses_initial_010() {
        assertThat(SemVer.nextFor(null, null)).isEqualTo("0.1.0");
        assertThat(SemVer.nextFor(null, "major")).isEqualTo("0.1.0");
    }

    @Test
    void next_for_default_is_minor_bump() {
        assertThat(SemVer.nextFor("1.2.3", null)).isEqualTo("1.3.0");
        assertThat(SemVer.nextFor("1.2.3", "minor")).isEqualTo("1.3.0");
    }

    @Test
    void next_for_supports_explicit_axis() {
        assertThat(SemVer.nextFor("1.2.3", "major")).isEqualTo("2.0.0");
        assertThat(SemVer.nextFor("1.2.3", "patch")).isEqualTo("1.2.4");
    }

    @Test
    void next_for_rejects_unknown_axis() {
        assertThatThrownBy(() -> SemVer.nextFor("1.2.3", "weird"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
