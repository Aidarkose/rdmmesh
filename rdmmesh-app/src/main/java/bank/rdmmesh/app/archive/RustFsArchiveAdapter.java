package bank.rdmmesh.app.archive;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.ArchivePort;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.SetObjectRetentionArgs;
import io.minio.StatObjectArgs;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;

/**
 * {@link ArchivePort} поверх S3-совместимого store'а (dev — RustFS,
 * Apache-2.0). minio-java работает против любого S3-API (RustFS = drop-in
 * MinIO-замена). Живёт в composition-root'е: {@code rdmmesh-audit} не имеет
 * права на S3-клиент (ArchUnit).
 *
 * <p><b>Object-Lock best-effort.</b> Bucket создаётся с
 * {@code objectLock(true)}; на каждый объект ставится GOVERNANCE-retention до
 * {@code retainUntil}. Если store не поддерживает Object-Lock API (RustFS
 * WORM-surface полностью не подтверждён, см. handoff E14.11 §2) — объект всё
 * равно записан, но {@code retentionApplied=false}; вызывающий фиксирует это
 * в манифесте (честно, без молчаливой потери WORM).
 */
public final class RustFsArchiveAdapter implements ArchivePort {

    private static final Logger log = LoggerFactory.getLogger(RustFsArchiveAdapter.class);

    private final MinioClient client;
    private final String bucket;
    private final RetentionMode lockMode;
    private volatile boolean bucketReady = false;

    public RustFsArchiveAdapter(
            String endpoint,
            String accessKey,
            String secretKey,
            String region,
            String bucket,
            RetentionMode lockMode) {
        this.bucket = bucket;
        this.lockMode = lockMode;
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .region(region)
                .build();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            // objectLock=true обязателен на момент создания — иначе
            // PutObjectRetention потом отвергается S3-семантикой.
            client.makeBucket(MakeBucketArgs.builder()
                    .bucket(bucket)
                    .objectLock(true)
                    .build());
            log.info("archive: bucket '{}' создан (object-lock enabled)", bucket);
        }
        bucketReady = true;
    }

    @Override
    public ArchiveResult putImmutable(
            String objectKey, byte[] content, String sha256Hex, OffsetDateTime retainUntil) {
        try {
            ensureBucket();
            ObjectWriteResponse w;
            try (var in = new ByteArrayInputStream(content)) {
                w = client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(in, content.length, -1)
                        .contentType("application/x-ndjson")
                        .build());
            }
            boolean retention = false;
            String note = null;
            try {
                client.setObjectRetention(SetObjectRetentionArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .config(new Retention(
                                lockMode,
                                ZonedDateTime.parse(retainUntil.toString())))
                        .bypassGovernanceMode(false)
                        .build());
                retention = true;
            } catch (Exception lockEx) {
                note = "Object-Lock retention не выставлен store'ом: " + lockEx.getMessage()
                        + " (объект записан; immutability — на bucket-policy)";
                log.warn("archive: {}", note);
            }
            return new ArchiveResult(
                    bucket, objectKey, w.etag(), content.length, retention, note);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "archive putImmutable failed for key=" + objectKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (GetObjectResponse r = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return r.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "archive get failed for key=" + objectKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
