package bank.rdmmesh.publishing.internal.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F4 SSRF-guard (E14 round 12, OWASP A10) для outbound-webhook'ов.
 * Закрывает finding E14.8 §1 F4: {@code SubscriptionService} валидировал
 * только схему {@code http(s)}, а {@code WebhookDeliveryWorker} POST'ил
 * подписанные payload'ы по любому host'у — SSRF во внутреннюю сеть /
 * cloud-metadata / admin-порт.
 *
 * <p><b>Safe-by-default.</b> Резолвит host в момент проверки
 * (DNS-rebinding-safe — вызывается delivery-worker'ом прямо перед connect)
 * и <b>запрещает</b> доставку, если ЛЮБОЙ из резолвленных адресов:
 *
 * <ul>
 *   <li>loopback / any-local / multicast — <b>всегда</b> (не настраивается);
 *   <li>link-local (вкл. cloud-metadata {@code 169.254.169.254}) —
 *       <b>всегда</b>;
 *   <li>приватный / ULA / CGNAT (10/8, 172.16/12, 192.168/16, 100.64/10,
 *       {@code fc00::/7}) — если адрес <b>не входит</b> в allowlist
 *       легитимных consumer-сетей.
 * </ul>
 *
 * <p>Allowlist приватных сетей — <b>пустой по умолчанию</b> (всё приватное
 * запрещено). Банк задаёт CIDR'ы реальных consumer-сетей через
 * {@code RDM_WEBHOOK_EGRESS_PRIVATE_ALLOWLIST} (comma-separated; открытый
 * вопрос Q56, network-policy). Публичные адреса разрешены (webhook наружу —
 * штатный сценарий распределения, SPEC §3.5/BR-15).
 *
 * <p>Метадата/loopback/link-local НЕ отключаются конфигом сознательно —
 * это самый острый вектор и легитимной нужды слать туда webhook нет.
 */
public final class EgressPolicy {

    private static final Logger log = LoggerFactory.getLogger(EgressPolicy.class);

    public static final String ALLOWLIST_ENV = "RDM_WEBHOOK_EGRESS_PRIVATE_ALLOWLIST";

    /** Приватные/ULA/CGNAT диапазоны, запрещённые если не в allowlist. */
    private static final List<Cidr> PRIVATE_RANGES = List.of(
            Cidr.parse("10.0.0.0/8"),
            Cidr.parse("172.16.0.0/12"),
            Cidr.parse("192.168.0.0/16"),
            Cidr.parse("100.64.0.0/10"), // CGNAT (вкл. часть cloud-metadata-прокси)
            Cidr.parse("fc00::/7")); // IPv6 ULA

    private final List<Cidr> privateAllowlist;

    public EgressPolicy(List<Cidr> privateAllowlist) {
        this.privateAllowlist = List.copyOf(privateAllowlist);
    }

    public static EgressPolicy fromEnv() {
        String raw = System.getenv(ALLOWLIST_ENV);
        List<Cidr> allow = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                try {
                    allow.add(Cidr.parse(p));
                } catch (IllegalArgumentException e) {
                    // Невалидный CIDR в конфиге — не «фейлим открыто»: логируем
                    // и игнорируем эту запись (политика остаётся строже).
                    log.error("egress: невалидный CIDR в {} — игнор: {} ({})",
                            ALLOWLIST_ENV, p, e.getMessage());
                }
            }
        }
        if (allow.isEmpty()) {
            log.warn("egress: private-allowlist пуст — ВСЕ приватные адресаты "
                    + "webhook'ов запрещены (safe-by-default). Задайте {} (Q56).",
                    ALLOWLIST_ENV);
        } else {
            log.info("egress: private-allowlist = {}", allow);
        }
        return new EgressPolicy(allow);
    }

    /**
     * Авторитетная проверка перед доставкой (резолв здесь = DNS-rebinding-safe).
     *
     * @throws EgressBlockedException если host пуст, не резолвится, либо любой
     *                                из адресов запрещён политикой.
     */
    public void check(String host) {
        if (host == null || host.isBlank()) {
            throw new EgressBlockedException("egress: пустой host");
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // На доставке нерезолвящийся host = блок (нельзя проверить → запрет).
            throw new EgressBlockedException("egress: host не резолвится: " + host);
        }
        for (InetAddress ip : addrs) {
            String reason = denyReason(ip);
            if (reason != null) {
                throw new EgressBlockedException(
                        "egress: " + host + " → " + ip.getHostAddress() + " запрещён ("
                                + reason + ")");
            }
        }
    }

    /**
     * Best-effort проверка при регистрации подписки: нерезолвящийся host
     * НЕ отвергается (DNS может быть валиден позже / резолвиться только из
     * другой сети) — авторитетный гард на доставке. Резолвящийся-запрещённый
     * — отвергаем сразу (быстрый фидбэк админу).
     *
     * @throws EgressBlockedException если host резолвится в запрещённый адрес.
     */
    public void checkAtRegistration(String host) {
        if (host == null || host.isBlank()) {
            throw new EgressBlockedException("egress: пустой host");
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return; // отложено на delivery-time guard
        }
        for (InetAddress ip : addrs) {
            String reason = denyReason(ip);
            if (reason != null) {
                throw new EgressBlockedException(
                        "egress: " + host + " → " + ip.getHostAddress() + " запрещён ("
                                + reason + ")");
            }
        }
    }

    /** @return причина запрета либо {@code null} если адрес разрешён. */
    private String denyReason(InetAddress ip) {
        if (ip.isLoopbackAddress()) {
            return "loopback";
        }
        if (ip.isAnyLocalAddress()) {
            return "any-local (0.0.0.0/::)";
        }
        if (ip.isMulticastAddress()) {
            return "multicast";
        }
        if (ip.isLinkLocalAddress()) {
            return "link-local/cloud-metadata"; // вкл. 169.254.169.254
        }
        for (Cidr c : PRIVATE_RANGES) {
            if (c.contains(ip)) {
                if (inAllowlist(ip)) {
                    return null; // приватный, но явно разрешён банком
                }
                return "private " + c + " (не в " + ALLOWLIST_ENV + ")";
            }
        }
        return null; // публичный — штатная доставка наружу
    }

    private boolean inAllowlist(InetAddress ip) {
        for (Cidr c : privateAllowlist) {
            if (c.contains(ip)) {
                return true;
            }
        }
        return false;
    }
}
