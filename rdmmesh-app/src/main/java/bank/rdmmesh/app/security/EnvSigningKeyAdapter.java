package bank.rdmmesh.app.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import bank.rdmmesh.api.port.SigningKeyPort;

/**
 * Реализация {@link SigningKeyPort} поверх переменных окружения. На пилоте — единственная
 * реализация; в проде её заменит Vault/SOPS-адаптер. SPEC §3.7 явно запрещает хранить
 * HMAC-ключ в БД, поэтому источником на dev-стенде остаётся env.
 *
 * <p><b>Rotation (E14 round 6).</b> {@link #fromEnv(String, String)} читает два env-var'а:
 * <ul>
 *   <li>{@code <VAR>} — primary-ключ: им подписываем ({@link #currentHmacKey()});</li>
 *   <li>{@code <VAR>_PREVIOUS} — опциональный «прежний» ключ на время overlap-окна:
 *       входит в {@link #acceptedHmacKeys()} вторым, чтобы verify принимал подписи,
 *       сделанные до ротации (для E7 — пока OM Event Subscription не переключился
 *       на новый ключ).</li>
 * </ul>
 * Вне ротации {@code <VAR>_PREVIOUS} не задан — {@code acceptedHmacKeys() == [primary]}.
 * Процедура — {@code docs/runbooks/hmac-key-rotation.md}.
 *
 * <p>Никаких "12345"-значений в коде — пустой primary валит старт сервиса
 * (см. {@link #fromEnv(String, String)}). {@code _PREVIOUS}, если задан, обязан
 * тоже быть ≥32 байт — мисконфиг падает на старте, а не молча.
 */
public final class EnvSigningKeyAdapter implements SigningKeyPort {

    /** Соглашение проекта (параллельно SPEC HMAC) — минимальная длина ключа. */
    public static final int MIN_KEY_BYTES = 32;

    /** Суффикс env-var'а для overlap-ключа на время ротации. */
    public static final String PREVIOUS_SUFFIX = "_PREVIOUS";

    /** [0] = primary (sign + verify), [1] = previous (verify only), если задан. */
    private final List<byte[]> keys;

    /** Single-key конструктор (backward-compatible: без overlap-ключа). */
    public EnvSigningKeyAdapter(byte[] key) {
        this(key, null);
    }

    /**
     * @param primary  ключ для подписи и проверки (обязателен, ≥32 байт)
     * @param previous опциональный overlap-ключ только для проверки; {@code null} вне ротации
     */
    public EnvSigningKeyAdapter(byte[] primary, byte[] previous) {
        if (primary == null || primary.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "HMAC key должен быть не короче " + MIN_KEY_BYTES + " байт (primary)");
        }
        List<byte[]> k = new ArrayList<>(2);
        k.add(primary.clone());
        if (previous != null) {
            if (previous.length < MIN_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "HMAC overlap-ключ (_PREVIOUS) короче " + MIN_KEY_BYTES + " байт");
            }
            k.add(previous.clone());
        }
        this.keys = List.copyOf(k);
    }

    /**
     * Читает primary из {@code envVarName} (фолбэк {@code fallbackForDev} только для
     * primary) и опциональный overlap-ключ из {@code envVarName + "_PREVIOUS"}.
     * У previous фолбэка нет — он осмыслен только при реальной ротации.
     */
    public static EnvSigningKeyAdapter fromEnv(String envVarName, String fallbackForDev) {
        String primary = System.getenv(envVarName);
        if (primary == null || primary.isBlank()) {
            primary = fallbackForDev;
        }
        Objects.requireNonNull(primary, "no value for " + envVarName);

        String previous = System.getenv(envVarName + PREVIOUS_SUFFIX);
        byte[] previousBytes = (previous == null || previous.isBlank())
                ? null
                : previous.getBytes(StandardCharsets.UTF_8);

        return new EnvSigningKeyAdapter(
                primary.getBytes(StandardCharsets.UTF_8), previousBytes);
    }

    @Override
    public byte[] currentHmacKey() {
        return keys.get(0).clone();
    }

    @Override
    public List<byte[]> acceptedHmacKeys() {
        List<byte[]> out = new ArrayList<>(keys.size());
        for (byte[] k : keys) {
            out.add(k.clone());
        }
        return List.copyOf(out);
    }
}
