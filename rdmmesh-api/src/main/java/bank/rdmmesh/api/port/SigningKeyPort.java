package bank.rdmmesh.api.port;

import java.util.List;

/**
 * Поставщик HMAC-ключа для подписи published-версий (SPEC §3.7 — ключи живут в
 * Vault/SOPS, не в БД). На пилоте реализация — env-var адаптер; в проде — Vault.
 * Замена адаптера не требует изменений в бизнес-логике publishing.
 *
 * <p><b>Rotation (E14 round 6).</b> Подпись всегда делается <em>primary</em>-ключом
 * ({@link #currentHmacKey()}); проверка — против <em>любого</em> из
 * {@link #acceptedHmacKeys()} (primary + опциональный «previous» на время overlap-окна
 * ротации). Это даёт zero-downtime ротацию: ввести новый primary, оставить старый как
 * previous до миграции контрагента (для E7 — OM Event Subscription), затем убрать
 * previous. Процедура — {@code docs/runbooks/hmac-key-rotation.md}.
 */
public interface SigningKeyPort {

    /**
     * Текущий (primary) HMAC-ключ для <em>подписи</em> {@code approval_signature}
     * published-версий и для recompute'а при verify. Не короче 32 байт.
     * На пилоте — env {@code RDM_HMAC_KEY}.
     */
    byte[] currentHmacKey();

    /**
     * Все ключи, валидные для <em>проверки</em> подписи, primary первым.
     * Во время overlap-окна ротации содержит {@code [primary, previous]}; вне
     * ротации — только {@code [primary]}.
     *
     * <p>Default — backward-compatible: реализации/моки/лямбды, написанные до
     * round 6, продолжают работать как single-key (verify == sign-key).
     * Реальный rotation-aware адаптер {@code EnvSigningKeyAdapter}
     * (env {@code RDM_HMAC_KEY} + {@code RDM_HMAC_KEY_PREVIOUS}) переопределяет метод.
     */
    default List<byte[]> acceptedHmacKeys() {
        return List.of(currentHmacKey());
    }
}
