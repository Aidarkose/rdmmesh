package bank.rdmmesh.publishing.internal.outbound;

import java.time.Duration;

/**
 * Экспоненциальный backoff для outbound webhook delivery.
 *
 * <p>Расписание (1-based attempts, attempts уже включает только что неудавшуюся попытку):
 * <pre>
 *   1 →  30s
 *   2 →  1m
 *   3 →  2m
 *   4 →  5m
 *   5 → 15m
 *   6 → 30m
 *   7 →  1h
 *   8 →  2h     (последняя retry-попытка)
 *   ≥ {@link #MAX_ATTEMPTS} → give up
 * </pre>
 *
 * <p>Чистая функция без I/O — легко покрыть unit-тестом.
 */
public final class Backoff {

    /** Максимум попыток до того, как worker сдаётся (см. {@code WebhookOutboxDao.markGivenUp}). */
    public static final int MAX_ATTEMPTS = 8;

    private static final Duration[] SCHEDULE = {
        Duration.ofSeconds(30),
        Duration.ofMinutes(1),
        Duration.ofMinutes(2),
        Duration.ofMinutes(5),
        Duration.ofMinutes(15),
        Duration.ofMinutes(30),
        Duration.ofHours(1),
        Duration.ofHours(2),
    };

    private Backoff() {}

    /** Задержка до следующей попытки. {@code attempts} — сколько провалов уже было (≥1). */
    public static Duration nextDelay(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1, got " + attempts);
        }
        int idx = Math.min(attempts - 1, SCHEDULE.length - 1);
        return SCHEDULE[idx];
    }

    public static boolean exhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }
}
