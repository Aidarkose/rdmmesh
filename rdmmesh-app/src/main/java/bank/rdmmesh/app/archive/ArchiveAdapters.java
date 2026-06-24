package bank.rdmmesh.app.archive;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.ArchivePort;
import io.minio.messages.RetentionMode;

/**
 * Фабрика {@link ArchivePort} из окружения (паттерн {@code EnvSigningKeyAdapter}).
 *
 * <p>Disabled-by-default: пустой {@code RDM_ARCHIVE_ENDPOINT} → no-op-stub
 * ({@link #disabled()}), сервис без RustFS не падает (как OM-lookup в E2).
 * Архивация — опциональный ops-flow, не hot-path.
 */
public final class ArchiveAdapters {

    private static final Logger log = LoggerFactory.getLogger(ArchiveAdapters.class);

    private ArchiveAdapters() {}

    public static ArchivePort fromEnv() {
        String endpoint = trim(System.getenv("RDM_ARCHIVE_ENDPOINT"));
        if (endpoint == null) {
            log.warn("archive: RDM_ARCHIVE_ENDPOINT пуст — ArchivePort disabled (no-op stub)");
            return disabled();
        }
        String bucket = orDefault(System.getenv("RDM_ARCHIVE_BUCKET"), "rdmmesh-audit-archive");
        String ak = orDefault(System.getenv("RDM_ARCHIVE_ACCESS_KEY"), "rustfsadmin");
        String sk = orDefault(System.getenv("RDM_ARCHIVE_SECRET_KEY"), "rustfsadmin");
        String region = orDefault(System.getenv("RDM_ARCHIVE_REGION"), "us-east-1");
        RetentionMode lockMode = parseLockMode(System.getenv("RDM_ARCHIVE_LOCK_MODE"));
        log.info("archive: ArchivePort enabled — endpoint={} bucket={} lockMode={}",
                endpoint, bucket, lockMode);
        return new RustFsArchiveAdapter(endpoint, ak, sk, region, bucket, lockMode);
    }

    /**
     * Q62 (E14 round 13): GOVERNANCE (default — admin с bypass снимает
     * retention) vs COMPLIANCE (необратимо до retain-until, regulator-mandated).
     * Невалидное значение → GOVERNANCE + error-лог (не «фейлим» архивацию,
     * но и не молча включаем более слабый/сильный режим без следа).
     */
    private static RetentionMode parseLockMode(String raw) {
        String v = trim(raw);
        if (v == null) {
            return RetentionMode.GOVERNANCE;
        }
        try {
            return RetentionMode.valueOf(v.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.error("archive: невалидный RDM_ARCHIVE_LOCK_MODE='{}' — fallback GOVERNANCE "
                    + "(допустимо: GOVERNANCE|COMPLIANCE)", v);
            return RetentionMode.GOVERNANCE;
        }
    }

    /** No-op: {@link ArchivePort#enabled()} = false, put/exists бросают ISE. */
    public static ArchivePort disabled() {
        return new ArchivePort() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public ArchiveResult putImmutable(
                    String objectKey, byte[] content, String sha256Hex, OffsetDateTime retainUntil) {
                throw new IllegalStateException(
                        "ArchivePort disabled — RDM_ARCHIVE_ENDPOINT не сконфигурирован");
            }

            @Override
            public byte[] get(String objectKey) {
                throw new IllegalStateException(
                        "ArchivePort disabled — RDM_ARCHIVE_ENDPOINT не сконфигурирован");
            }

            @Override
            public boolean exists(String objectKey) {
                throw new IllegalStateException(
                        "ArchivePort disabled — RDM_ARCHIVE_ENDPOINT не сконфигурирован");
            }
        };
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String orDefault(String v, String def) {
        String t = trim(v);
        return t == null ? def : t;
    }
}
