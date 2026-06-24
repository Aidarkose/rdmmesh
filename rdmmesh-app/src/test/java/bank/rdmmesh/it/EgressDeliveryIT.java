package bank.rdmmesh.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.publishing.internal.egress.Cidr;
import bank.rdmmesh.publishing.internal.egress.EgressPolicy;
import bank.rdmmesh.publishing.internal.outbound.WebhookDeliveryWorker;

/**
 * E14.15 — закрывает E14.12 §3 #2 (F4 SSRF-guard, delivery-time): доказывает,
 * что {@link EgressPolicy} дискриминирует адресата прямо в
 * {@link WebhookDeliveryWorker} (DNS-rebinding-safe точка, перед connect):
 *
 * <ul>
 *   <li><b>blocked</b> — подписка на cloud-metadata
 *       ({@code 169.254.169.254}, hard-deny, не настраивается): outbox →
 *       {@code GIVE_UP: egress …} <b>без retry</b> ({@code delivered_at}
 *       проставлен, чтобы остановить ретраи), {@code last_delivery_status
 *       = FAILED};</li>
 *   <li><b>delivered</b> — подписка на приватный адрес, явно внесённый в
 *       allowlist (Q56-путь {@code RDM_WEBHOOK_EGRESS_PRIVATE_ALLOWLIST}),
 *       на котором поднят реальный HTTP-приёмник: egress пропускает →
 *       POST доставлен (2xx) → {@code delivered_at} проставлен,
 *       {@code last_error IS NULL}, {@code last_delivery_status = OK}.</li>
 * </ul>
 *
 * <p><b>Почему «приватный-в-allowlist», а не «публичный».</b> E14.12 §3 #2
 * формулирует «публичный → доставка», но в герметичном IT публичного
 * 2xx-приёмника нет, а loopback egress'ом hard-deny (нельзя поднять сервер
 * на {@code 127.0.0.1}). Site-local адрес хоста — приватный (в
 * {@code PRIVATE_RANGES}), поэтому доставка туда дополнительно доказывает,
 * что allowlist (единственное, что должен задать банк по Q56) реально
 * открывает egress. Публичный-разрешён покрыт герметичным
 * {@code EgressPolicyTest} (8/8, E14.12 §1) на уровне политики.
 *
 * <p>Локально {@code Skipped} (Docker-Desktop, E14.9 §2); реально гоняется
 * на CI (E14 round 14 CI-gating). delivered-кейс дополнительно требует
 * site-local NIC (на CI-ubuntu есть; иначе {@code assumeTrue}-skip — без
 * ложного провала).
 */
final class EgressDeliveryIT extends PostgresIT {

    /** signature column: ^[a-f0-9]{64}$ (V040). Значение для теста не считается. */
    private static final String SIG64 = "0".repeat(64);

    private static UUID seedSubscription(String url) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = adminConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO publishing.webhook_subscription"
                                + " (id, url, secret_id, created_by)"
                                + " VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, url);
            ps.setString(3, "vault://it/egress-" + id);
            ps.setObject(4, UUID.randomUUID());
            ps.executeUpdate();
        }
        return id;
    }

    /** due-строка outbox (next_attempt_at default now(), attempts 0, not delivered). */
    private static UUID seedOutbox(UUID subscriptionId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = adminConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO publishing.webhook_outbox"
                                + " (id, subscription_id, event_id, event_type,"
                                + "  payload, signature)"
                                + " VALUES (?, ?, ?, 'VersionPublished', ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, subscriptionId);
            ps.setObject(3, UUID.randomUUID());
            ps.setString(4, "{\"it\":\"egress-delivery\"}");
            ps.setString(5, SIG64);
            ps.executeUpdate();
        }
        return id;
    }

    private record Outbox(boolean delivered, String lastError, int attempts) {}

    private static Outbox outbox(UUID id) throws SQLException {
        try (Connection c = adminConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT delivered_at, last_error, attempts"
                                + " FROM publishing.webhook_outbox WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new Outbox(
                        rs.getObject(1) != null, rs.getString(2), rs.getInt(3));
            }
        }
    }

    private static String deliveryStatus(UUID subscriptionId) throws SQLException {
        try (Connection c = adminConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT last_delivery_status"
                                + " FROM publishing.webhook_subscription WHERE id = ?")) {
            ps.setObject(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    @Test
    void cloudMetadataTargetIsGivenUpByEgressWithoutRetry() throws SQLException {
        EgressPolicy egress = new EgressPolicy(List.of()); // allowlist пуст
        UUID sub = seedSubscription("http://169.254.169.254/latest/meta-data/");
        UUID box = seedOutbox(sub);

        new WebhookDeliveryWorker(appJdbi(), 60, egress).drainOnce();

        Outbox o = outbox(box);
        assertThat(o.delivered())
                .as("GIVE_UP проставляет delivered_at — ретраи остановлены")
                .isTrue();
        assertThat(o.attempts()).as("markGivenUp инкрементирует attempts").isEqualTo(1);
        assertThat(o.lastError())
                .startsWith("GIVE_UP:")
                .contains("egress:")
                .contains("link-local/cloud-metadata");
        assertThat(deliveryStatus(sub)).isEqualTo("FAILED");
    }

    @Test
    void allowlistedPrivateTargetIsDelivered() throws Exception {
        InetAddress siteLocal = firstSiteLocalIpv4();
        assumeTrue(siteLocal != null,
                "нет site-local NIC — delivered-кейс пропущен (на CI-ubuntu есть)");

        AtomicReference<String> seenEventId = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(siteLocal, 0), 0);
        server.createContext("/hook", exchange -> {
            seenEventId.set(exchange.getRequestHeaders().getFirst("X-RDM-Event-Id"));
            seenBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        try {
            String host = siteLocal.getHostAddress();
            int port = server.getAddress().getPort();
            // Приватный адрес запрещён по умолчанию; allowlist /32 (Q56-путь)
            // должен открыть egress именно для него.
            EgressPolicy egress = new EgressPolicy(List.of(Cidr.parse(host + "/32")));
            UUID sub = seedSubscription("http://" + host + ":" + port + "/hook");
            UUID box = seedOutbox(sub);

            new WebhookDeliveryWorker(appJdbi(), 60, egress).drainOnce();

            assertThat(seenBody.get())
                    .as("приёмник получил исходный payload байт-в-байт")
                    .isEqualTo("{\"it\":\"egress-delivery\"}");
            assertThat(seenEventId.get()).as("X-RDM-Event-Id проставлен").isNotBlank();

            Outbox o = outbox(box);
            assertThat(o.delivered()).as("2xx → markDelivered").isTrue();
            assertThat(o.lastError()).as("успех очищает last_error").isNull();
            assertThat(deliveryStatus(sub)).isEqualTo("OK");
        } finally {
            server.stop(0);
        }
    }

    /** Первый non-loopback site-local IPv4 (RFC1918) активного интерфейса. */
    private static InetAddress firstSiteLocalIpv4() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address
                            && a.isSiteLocalAddress()
                            && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress()) {
                        return a;
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null; // нет доступа к NIC — кейс будет assumeTrue-скипнут
        }
    }
}
