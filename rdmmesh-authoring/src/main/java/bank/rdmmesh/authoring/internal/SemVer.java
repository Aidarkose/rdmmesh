package bank.rdmmesh.authoring.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Минимальный SemVer parser/bumper. SPEC §3.4 говорит о {@code 1.0.0 / 1.1.0 / 2.0.0}
 * — только major.minor.patch + опциональный pre-release suffix.
 *
 * <p>Зависимостей-«Семивер» специально нет — функциональность здесь занимает один файл,
 * добавлять {@code com.vdurmont:semver4j} ради этого избыточно.
 */
public final class SemVer {

    /** Совпадает с pattern'ом из {@code rdmmesh-spec/schema/common/types.json#semver}. */
    private static final Pattern PATTERN = Pattern.compile(
            "^(?<major>0|[1-9]\\d*)\\.(?<minor>0|[1-9]\\d*)\\.(?<patch>0|[1-9]\\d*)"
                    + "(?:-(?<pre>[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    public final int major;
    public final int minor;
    public final int patch;
    public final String preRelease;

    private SemVer(int major, int minor, int patch, String preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
    }

    public static SemVer parse(String text) {
        if (text == null) throw new IllegalArgumentException("semver is null");
        Matcher m = PATTERN.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("Bad semver: " + text);
        return new SemVer(
                Integer.parseInt(m.group("major")),
                Integer.parseInt(m.group("minor")),
                Integer.parseInt(m.group("patch")),
                m.group("pre"));
    }

    public static boolean isValid(String text) {
        return text != null && PATTERN.matcher(text).matches();
    }

    public String render() {
        StringBuilder sb = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
        if (preRelease != null) sb.append('-').append(preRelease);
        return sb.toString();
    }

    @Override
    public String toString() {
        return render();
    }

    public SemVer bumpMajor() { return new SemVer(major + 1, 0, 0, null); }
    public SemVer bumpMinor() { return new SemVer(major, minor + 1, 0, null); }
    public SemVer bumpPatch() { return new SemVer(major, minor, patch + 1, null); }

    /**
     * Удобный entry-point для сервиса: на основе предыдущей published-версии
     * (или {@code null}, если версий ещё нет) вычислить semver для нового draft'а.
     *
     * @param previous последняя published-version, или {@code null} если CodeSet пустой
     * @param bump     {@code "major" | "minor" | "patch"} (default — minor)
     */
    public static String nextFor(String previous, String bump) {
        if (previous == null) return "0.1.0";
        SemVer prev = parse(previous);
        return switch (bump == null ? "minor" : bump.toLowerCase(java.util.Locale.ROOT)) {
            case "major" -> prev.bumpMajor().render();
            case "patch" -> prev.bumpPatch().render();
            case "minor" -> prev.bumpMinor().render();
            default -> throw new IllegalArgumentException("Unknown bump: " + bump);
        };
    }
}
