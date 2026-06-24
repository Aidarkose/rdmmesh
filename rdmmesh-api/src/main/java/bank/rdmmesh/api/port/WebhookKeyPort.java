package bank.rdmmesh.api.port;

import java.util.List;

/**
 * Резолвит {@code secret_id} subscription'а (Vault path / SOPS key id, SPEC §3.5,
 * `webhook-subscription.json`) в HMAC-ключ доставки. Сам ключ в БД не хранится —
 * там лежит только pointer; реальное значение приходит из Vault/SOPS-адаптера в проде.
 *
 * <p>На пилоте — env-адаптер ({@code RDM_WEBHOOK_KEY_<UPPER(secretId)>}); fallback
 * на общий dev-ключ задаётся на уровне адаптера. Замена реализации не требует правок
 * в worker'е доставки.
 *
 * <p>Параллельно к {@link SigningKeyPort} (который отдаёт серверный HMAC-ключ для
 * подписи snapshot'ов) — этот порт отдаёт per-subscription ключ для подписи payload'а
 * outbound webhook'а. Разные ключи, разные политики ротации.
 */
public interface WebhookKeyPort {

    /**
     * Возвращает primary-ключ (≥32 байт) для <em>подписи</em> payload'а outbound
     * webhook'а. Бросает {@link IllegalArgumentException} если secret_id неизвестен —
     * это считается мисконфигурацией subscription'а, а не транзиентной ошибкой доставки.
     */
    byte[] resolveKey(String secretId);

    /**
     * E14 round 6 — все ключи subscription'а (primary первым), валидные во время
     * overlap-окна ротации. Outbound-доставка подписывает payload <em>один раз</em>
     * primary-ключом на enqueue (см. {@code OutboxOutboundAdapter}); ротация на
     * стороне RDM — это смена primary, а двух-ключевую verify-фазу держит consumer
     * (E9 §3 #4). Метод предоставляется для симметрии с {@link SigningKeyPort} и
     * на случай будущего inbound-verify outbound-подписей.
     *
     * <p>Default — backward-compatible single-key. {@code EnvWebhookKeyAdapter}
     * переопределяет, добавляя {@code RDM_WEBHOOK_KEY_<ID>_PREVIOUS}.
     */
    default List<byte[]> resolveAllKeys(String secretId) {
        return List.of(resolveKey(secretId));
    }
}
