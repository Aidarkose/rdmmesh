package bank.rdmmesh.publishing.internal.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void schedule_is_monotonically_increasing() {
        Duration prev = Duration.ZERO;
        for (int attempts = 1; attempts <= Backoff.MAX_ATTEMPTS; attempts++) {
            Duration d = Backoff.nextDelay(attempts);
            assertThat(d).isGreaterThanOrEqualTo(prev);
            prev = d;
        }
    }

    @Test
    void first_attempt_30s() {
        assertThat(Backoff.nextDelay(1)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void after_max_attempts_keeps_using_last_slot() {
        // Безопасное поведение: даже если кто-то вызвал nextDelay для attempts > MAX_ATTEMPTS,
        // не падаем, а отдаём «крайний» интервал (2h). Нельзя возвращать 0 или Duration.MAX —
        // это нарушит инварианты worker'а (бесконечная очередь повторов).
        assertThat(Backoff.nextDelay(Backoff.MAX_ATTEMPTS)).isEqualTo(Duration.ofHours(2));
        assertThat(Backoff.nextDelay(Backoff.MAX_ATTEMPTS + 5)).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void exhausted_at_max_attempts() {
        assertThat(Backoff.exhausted(Backoff.MAX_ATTEMPTS - 1)).isFalse();
        assertThat(Backoff.exhausted(Backoff.MAX_ATTEMPTS)).isTrue();
        assertThat(Backoff.exhausted(Backoff.MAX_ATTEMPTS + 1)).isTrue();
    }

    @Test
    void zero_or_negative_attempts_rejected() {
        assertThatThrownBy(() -> Backoff.nextDelay(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Backoff.nextDelay(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
