package bank.rdmmesh.audit.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.ArchivePort;
import bank.rdmmesh.audit.internal.dao.ArchiveManifestDao;
import bank.rdmmesh.audit.internal.dao.ArchiveManifestDao.ManifestRow;
import bank.rdmmesh.audit.internal.dao.AuditLogDao;
import bank.rdmmesh.audit.internal.dao.AuditLogDao.ExportRow;

/**
 * E14 round 10 — заливка месячного сегмента {@code audit_log} в immutable
 * object-store через {@link ArchivePort} + запись факта в
 * {@code audit.archive_manifest} (источник истины для
 * {@code drop_audit_partition_if_archived(text)}, V074).
 *
 * <p>Сегмент = помесячная партиция (имя совпадает с
 * {@code audit.ensure_audit_partition} V073 — {@code audit_log_yYYYYmMM} —
 * чтобы drop-guard матчился по {@code segment_label}). Сериализация —
 * детерминированная ndjson через {@link AuditExportWriter} (rows ORDER BY id
 * ASC), SHA-256 над байтами хранится в манифесте для offline-verify аудитором.
 *
 * <p>{@code rdmmesh-audit} не зависит от S3-клиента (ArchUnit): вся работа со
 * store'ом — через {@link ArchivePort} (rdmmesh-api).
 */
public final class AuditArchiveService {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveService.class);
    private static final int PAGE = 1000;

    private final Jdbi jdbi;
    private final ArchivePort archive;

    public AuditArchiveService(Jdbi jdbi, ArchivePort archive) {
        this.jdbi = jdbi;
        this.archive = archive;
    }

    /** Результат архивации одного сегмента. */
    public record Result(
            String segmentLabel,
            String bucket,
            String objectKey,
            long fromId,
            long toId,
            long rowCount,
            String contentSha256,
            boolean retentionApplied,
            boolean manifestInserted,
            String note) {}

    /**
     * Заархивировать сегмент {@code audit_log} за указанный месяц.
     *
     * @throws IllegalStateException    если ArchivePort disabled.
     * @throws IllegalArgumentException если в сегменте нет строк.
     */
    public Result archiveMonth(int year, int month, UUID admin) {
        if (!archive.enabled()) {
            throw new IllegalStateException(
                    "ArchivePort disabled — RDM_ARCHIVE_ENDPOINT не сконфигурирован");
        }
        String segmentLabel = String.format("audit_log_y%dm%02d", year, month);
        String objectKey = String.format("audit/%d/%02d/%s.ndjson", year, month, segmentLabel);

        OffsetDateTime fromTs = OffsetDateTime.of(year, month, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime toTs = fromTs.plusMonths(1);

        long snapshotMaxId = jdbi.withExtension(AuditLogDao.class, AuditLogDao::findMaxId)
                .orElse(0L);
        if (snapshotMaxId == 0L) {
            throw new IllegalArgumentException("audit_log пуст — нечего архивировать");
        }

        List<ExportRow> rows = new ArrayList<>();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(buf, StandardCharsets.UTF_8)) {
            while (true) {
                // offset = уже собрано: snapshotMaxId фиксирован + ORDER BY id ASC
                // → стабильная пагинация без дублей/пропусков на границах.
                long offset = rows.size();
                List<ExportRow> page = jdbi.withExtension(AuditLogDao.class, dao ->
                        dao.findExportPage(snapshotMaxId, null, null, null, null,
                                fromTs, toTs, null, PAGE, offset));
                if (page.isEmpty()) {
                    break;
                }
                for (ExportRow r : page) {
                    AuditExportWriter.writeNdjsonRow(w, r);
                    rows.add(r);
                }
                if (page.size() < PAGE) {
                    break;
                }
            }
            w.flush();
        } catch (IOException e) {
            throw new IllegalStateException("ndjson-сериализация сегмента упала: " + e.getMessage(), e);
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "сегмент " + segmentLabel + " пуст за " + year + "-" + month
                            + " — нечего архивировать");
        }

        byte[] content = buf.toByteArray();
        String sha = sha256Hex(content);
        long fromId = rows.get(0).id();
        long toId = rows.get(rows.size() - 1).id();
        // Retention-горизонт: верхняя граница месяца + 10 лет (SPEC §3.7 —
        // DEPRECATED/IFRS9 10 лет; для общего audit 7, берём строже).
        OffsetDateTime retainUntil = toTs.plusYears(10);

        ArchivePort.ArchiveResult put =
                archive.putImmutable(objectKey, content, sha, retainUntil);

        int inserted = jdbi.withExtension(ArchiveManifestDao.class, dao -> dao.insert(
                segmentLabel, fromId, toId, rows.size(), sha,
                put.bucket(), put.objectKey(), put.etag(), put.sizeBytes(),
                put.retentionApplied(), retainUntil, admin));

        log.info("archive: сегмент {} → {}/{} rows={} sha256={} retention={} manifest_inserted={}",
                segmentLabel, put.bucket(), put.objectKey(), rows.size(), sha,
                put.retentionApplied(), inserted == 1);

        return new Result(
                segmentLabel, put.bucket(), put.objectKey(), fromId, toId, rows.size(),
                sha, put.retentionApplied(), inserted == 1, put.note());
    }

    /** Результат independent-verify заархивированного сегмента. */
    public record VerifyResult(
            String segmentLabel,
            String bucket,
            String objectKey,
            String manifestSha256,
            String computedSha256,
            Long manifestSize,
            long fetchedSize,
            boolean retentionApplied,
            boolean verified) {}

    /**
     * Скачать сегмент из immutable-store и сверить SHA-256 с
     * {@code archive_manifest} (compliance independent-verify; закрывает
     * E14.11 §3 #5). Read-only — допустим RDM_AUDITOR.
     *
     * @throws IllegalStateException    ArchivePort disabled.
     * @throws IllegalArgumentException сегмент не значится в манифесте.
     */
    public VerifyResult verifySegment(String segmentLabel) {
        if (!archive.enabled()) {
            throw new IllegalStateException(
                    "ArchivePort disabled — RDM_ARCHIVE_ENDPOINT не сконфигурирован");
        }
        ManifestRow m = jdbi.withExtension(ArchiveManifestDao.class,
                        dao -> dao.findBySegment(segmentLabel))
                .orElseThrow(() -> new IllegalArgumentException(
                        "сегмент " + segmentLabel + " не значится в audit.archive_manifest"));

        byte[] bytes = archive.get(m.objectKey());
        String computed = sha256Hex(bytes);
        boolean shaOk = computed.equals(m.contentSha256());
        boolean sizeOk = m.sizeBytes() == null || m.sizeBytes() == bytes.length;

        VerifyResult r = new VerifyResult(
                segmentLabel, m.bucket(), m.objectKey(),
                m.contentSha256(), computed, m.sizeBytes(), bytes.length,
                m.retentionApplied(), shaOk && sizeOk);
        log.info("archive verify: segment={} verified={} (sha_ok={} size_ok={})",
                segmentLabel, r.verified(), shaOk, sizeOk);
        return r;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
