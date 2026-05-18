package bank.rdmmesh.publishing.internal.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * F4 SSRF-guard (E14 round 12). Literal-IP host'ы — {@code InetAddress}
 * не делает DNS для литералов, тест герметичен (без сети).
 */
final class EgressPolicyTest {

    private final EgressPolicy denyAllPrivate = new EgressPolicy(List.of());
    private final EgressPolicy allowTen =
            new EgressPolicy(List.of(Cidr.parse("10.0.0.0/8")));

    private static void assertBlocked(EgressPolicy p, String host) {
        assertThatThrownBy(() -> p.check(host))
                .as("должен быть запрещён: %s", host)
                .isInstanceOf(EgressBlockedException.class);
    }

    private static void assertAllowed(EgressPolicy p, String host) {
        assertThatCode(() -> p.check(host))
                .as("должен быть разрешён: %s", host)
                .doesNotThrowAnyException();
    }

    @Test
    void cloudMetadataAndLinkLocalAlwaysBlocked() {
        assertBlocked(denyAllPrivate, "169.254.169.254"); // cloud-metadata (link-local)
        assertBlocked(allowTen, "169.254.169.254"); // даже с allowlist — hard deny
        assertBlocked(denyAllPrivate, "169.254.1.1");
    }

    @Test
    void loopbackAnylocalMulticastAlwaysBlocked() {
        assertBlocked(denyAllPrivate, "127.0.0.1");
        assertBlocked(denyAllPrivate, "::1");
        assertBlocked(denyAllPrivate, "0.0.0.0");
        assertBlocked(denyAllPrivate, "224.0.0.1"); // multicast
    }

    @Test
    void privateBlockedByDefaultAllowedOnlyViaAllowlist() {
        assertBlocked(denyAllPrivate, "10.1.2.3");
        assertBlocked(denyAllPrivate, "192.168.5.5");
        assertBlocked(denyAllPrivate, "172.16.9.9");
        assertBlocked(denyAllPrivate, "100.100.100.200"); // CGNAT 100.64/10

        assertAllowed(allowTen, "10.1.2.3"); // в allowlist 10.0.0.0/8
        assertBlocked(allowTen, "192.168.5.5"); // не в allowlist
    }

    @Test
    void ipv6UlaBlockedUnlessAllowlisted() {
        assertBlocked(denyAllPrivate, "fd12:3456:789a::1"); // ULA fc00::/7
        EgressPolicy allowUla = new EgressPolicy(List.of(Cidr.parse("fc00::/7")));
        assertAllowed(allowUla, "fd12:3456:789a::1");
    }

    @Test
    void publicAddressesAllowed() {
        assertAllowed(denyAllPrivate, "8.8.8.8");
        assertAllowed(denyAllPrivate, "93.184.216.34");
        assertAllowed(denyAllPrivate, "2001:4860:4860::8888"); // публичный IPv6
    }

    @Test
    void emptyOrBlankHostBlocked() {
        assertThatThrownBy(() -> denyAllPrivate.check(null))
                .isInstanceOf(EgressBlockedException.class);
        assertThatThrownBy(() -> denyAllPrivate.check("  "))
                .isInstanceOf(EgressBlockedException.class);
    }

    @Test
    void registrationCheckSkipsUnresolvableButBlocksResolvedDenied() {
        // Нерезолвящийся host — на регистрации не отвергаем (delivery-guard
        // авторитетен). Литерал-IP резолвится без DNS → запрещённый блокнётся.
        assertThatCode(() ->
                        denyAllPrivate.checkAtRegistration("nonexistent.invalid.host.example"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> denyAllPrivate.checkAtRegistration("169.254.169.254"))
                .isInstanceOf(EgressBlockedException.class);
    }

    @Test
    void cidrParsesAndMatchesV4AndV6() {
        Cidr v4 = Cidr.parse("10.20.0.0/16");
        assertThat(v4.contains(addr("10.20.5.5"))).isTrue();
        assertThat(v4.contains(addr("10.21.0.1"))).isFalse();
        assertThat(v4.contains(addr("::1"))).isFalse(); // другое семейство

        Cidr v6 = Cidr.parse("fc00::/7");
        assertThat(v6.contains(addr("fd00::abcd"))).isTrue();
        assertThat(v6.contains(addr("fe80::1"))).isFalse();

        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InetAddress addr(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
