package bank.rdmmesh.ownership.internal.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import bank.rdmmesh.api.port.SigningKeyPort;

/**
 * Проверяет подпись OM webhook'а: {@code X-OM-Signature: sha256=<hex>}, где hex —
 * HMAC-SHA-256 от raw request body, ключом {@link SigningKeyPort#currentHmacKey()}.
 *
 * <p>Формат заголовка совпадает с GitHub-style ({@code sha256=<hex>}) — он же
 * настраивается в OM Event Subscription. Устойчив к timing-атакам через
 * {@link MessageDigest#isEqual(byte[], byte[])}.
 *
 * <p><b>Rotation (E14 round 6).</b> Подпись проверяется против <em>любого</em> из
 * {@link SigningKeyPort#acceptedHmacKeys()} (primary + опциональный previous). Это
 * даёт zero-downtime ротацию inbound-ключа OM webhook'а ({@code RDM_OM_WEBHOOK_HMAC_KEY}):
 * пока OM Event Subscription не переключился на новый секрет, RDM продолжает
 * принимать подписи под старым (previous) ключом. Все ключи перебираются без
 * раннего выхода — какой именно ключ совпал, по таймингу не утекает (число ключей
 * 1–2 и не секретно). Процедура — {@code docs/runbooks/hmac-key-rotation.md}.
 */
public final class HmacVerifier {

    private static final String ALGO = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final SigningKeyPort signingKey;

    public HmacVerifier(SigningKeyPort signingKey) {
        this.signingKey = signingKey;
    }

    /**
     * @return true если header'ный {@code sha256=<hex>} совпал с recompute'ом по body;
     *         false — если подпись отсутствует, имеет неверный формат или не совпала.
     */
    public boolean verify(String headerValue, byte[] body) {
        if (headerValue == null || body == null) return false;
        String trimmed = headerValue.trim();
        String hex = trimmed.startsWith(PREFIX) ? trimmed.substring(PREFIX.length()) : trimmed;
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        // E14 round 6: принимаем подпись под любым accepted-ключом (primary
        // ИЛИ previous на время overlap-окна ротации). Перебор без раннего
        // выхода: OR накапливаем, чтобы не утекал по таймингу индекс совпавшего ключа.
        boolean ok = false;
        for (byte[] key : signingKey.acceptedHmacKeys()) {
            ok |= MessageDigest.isEqual(presented, compute(body, key));
        }
        return ok;
    }

    /** Полезно для тестов и для кросс-системной отладки. */
    public static String sign(byte[] body, byte[] key) {
        return PREFIX + HexFormat.of().formatHex(compute(body, key));
    }

    private static byte[] compute(byte[] body, byte[] key) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(key, ALGO));
            return mac.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA-256 недоступен", e);
        }
    }

    /** Helper для логирования: pretty-print без раскрытия ключа. */
    public static String headerName() {
        return "X-OM-Signature";
    }

    static {
        // sanity check: сразу падаем, если runtime без HMAC-SHA-256.
        try {
            Mac.getInstance(ALGO);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
