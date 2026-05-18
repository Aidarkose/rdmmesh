package bank.rdmmesh.publishing.internal.outbound;

import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA-256 helper для outbound webhook'ов. Подпись проставляется один раз при
 * enqueue и хранится в {@code webhook_outbox.signature} (CHECK 64-hex), worker
 * прокидывает её в {@code X-RDM-Signature: sha256=<hex>} без обращения к Vault.
 */
public final class WebhookHmac {

    public static final String HEADER = "X-RDM-Signature";
    public static final String PREFIX = "sha256=";
    private static final String ALGO = "HmacSHA256";

    private WebhookHmac() {}

    /** Возвращает hex без префикса — пригоден для записи в БД (CHECK ^[a-f0-9]{64}$). */
    public static String hexSignature(byte[] payload, byte[] key) {
        return HexFormat.of().formatHex(compute(payload, key));
    }

    /** Готовый header value: {@code sha256=<hex>}. */
    public static String headerValue(String hex) {
        return PREFIX + hex;
    }

    /** Constant-time сверка для тестов / verify-utility. */
    public static boolean verify(byte[] payload, byte[] key, String headerValue) {
        if (headerValue == null) return false;
        String trimmed = headerValue.trim();
        String hex = trimmed.startsWith(PREFIX) ? trimmed.substring(PREFIX.length()) : trimmed;
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(presented, compute(payload, key));
    }

    private static byte[] compute(byte[] payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(key, ALGO));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA-256 недоступен", e);
        }
    }
}
