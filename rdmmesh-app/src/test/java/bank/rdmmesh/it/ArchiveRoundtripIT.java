package bank.rdmmesh.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import bank.rdmmesh.api.port.ArchivePort;
import bank.rdmmesh.app.archive.RustFsArchiveAdapter;
import bank.rdmmesh.audit.internal.AuditArchiveService;
import io.minio.messages.RetentionMode;

/**
 * E14 round 14 (R10 follow-up, E14.11 §3 #2) — автоматизированный
 * archive-roundtrip: реальный Postgres + реальный RustSF-Testcontainer.
 * Переводит ручной {@code mc}-smoke (E14.11 §5.2) в CI-гейт.
 *
 * <p>seed audit_log → {@link AuditArchiveService#archiveMonth} через
 * {@link RustFsArchiveAdapter} → проверка: манифест-строка, объект в
 * RustFS ({@code ArchivePort.exists}), independent
 * {@link AuditArchiveService#verifySegment} (скачать + пересчитать SHA-256).
 *
 * <p>Локально {@code Skipped} (Docker-Desktop, E14.9 §2); на CI с
 * нативным dockerd — оба контейнера (Postgres из {@link PostgresIT} +
 * RustFS ниже) реально поднимаются.
 */
final class ArchiveRoundtripIT extends PostgresIT {

    private static final String HASH64 = "b".repeat(64);

    @Container
    @SuppressWarnings("resource") // lifecycle — @Testcontainers extension
    private static final GenericContainer<?> RUSTFS =
            new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.0-beta.3"))
                    .withExposedPorts(9000)
                    .withEnv("RUSTFS_ACCESS_KEY", "rustfsadmin")
                    .withEnv("RUSTFS_SECRET_KEY", "rustfsadmin")
                    // RustFS отдаёт 403 на / без auth = S3 API живой.
                    .waitingFor(Wait.forHttp("/").forStatusCode(403));

    @Test
    void archiveThenManifestObjectAndVerify() throws Exception {
        String endpoint = "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
        ArchivePort archive = new RustFsArchiveAdapter(
                endpoint, "rustfsadmin", "rustfsadmin", "us-east-1",
                "rdmmesh-audit-archive-it", RetentionMode.GOVERNANCE);

        // Уникальный месяц (не пересекается с прочими IT в shared-БД).
        // 2027-09 партиции нет (V073 создал 2026-05/06+DEFAULT) → DEFAULT
        // safety-net; findExportPage фильтрует по occurred_at-окну.
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            for (int n = 1; n <= 3; n++) {
                st.execute("INSERT INTO audit.audit_log "
                        + "(event_id, event_type, occurred_at, payload, payload_canonical, entry_hash) "
                        + "VALUES ('" + UUID.randomUUID() + "', 'IT_ARCH', "
                        + "'2027-09-10T0" + n + ":00:00Z'::timestamptz, "
                        + "'{}'::jsonb, '{}', '" + HASH64 + "')");
            }
        }

        Jdbi jdbi = appJdbi();
        AuditArchiveService svc = new AuditArchiveService(jdbi, archive);
        UUID admin = UUID.randomUUID();

        AuditArchiveService.Result r = svc.archiveMonth(2027, 9, admin);

        assertThat(r.segmentLabel()).isEqualTo("audit_log_y2027m09");
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.manifestInserted()).isTrue();
        assertThat(r.contentSha256()).matches("[a-f0-9]{64}");

        // Объект реально в RustFS.
        assertThat(archive.exists(r.objectKey()))
                .as("объект сегмента в RustFS").isTrue();

        // Манифест-строка.
        try (Connection c = adminConnection();
                Statement st = c.createStatement();
                var rs = st.executeQuery(
                        "SELECT row_count, retention_applied FROM audit.archive_manifest "
                                + "WHERE segment_label='audit_log_y2027m09'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(3);
        }

        // Independent verify: скачать из RustFS, пересчитать SHA-256 vs манифест.
        AuditArchiveService.VerifyResult v = svc.verifySegment("audit_log_y2027m09");
        assertThat(v.verified()).isTrue();
        assertThat(v.computedSha256()).isEqualTo(r.contentSha256());

        // Повторный архив идемпотентен (манифест ON CONFLICT DO NOTHING).
        AuditArchiveService.Result again = svc.archiveMonth(2027, 9, admin);
        assertThat(again.manifestInserted()).isFalse();
    }
}
