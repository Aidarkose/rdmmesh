package bank.rdmmesh.distribution.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.port.RelationalReadPort.RelationalItem;

/**
 * Pure-тесты effective-time фильтра distribution'а (Stage 7b, in-memory над rd_data-строками).
 * Семантика daterange {@code [from, to)}: from включительно, to исключительно, NULL — открыто.
 */
class DistributionServiceTest {

    private static RelationalItem item(String from, String to) {
        return new RelationalItem(
                List.of("A"), null, "лейбл", "label", null, null,
                Map.of(), 0, "ACTIVE", from, to);
    }

    private static final LocalDate D = LocalDate.parse("2026-06-10");

    @Test
    void open_bounds_always_effective() {
        assertThat(DistributionService.effectiveAt(item(null, null), D)).isTrue();
    }

    @Test
    void from_inclusive_to_exclusive() {
        RelationalItem i = item("2026-01-01", "2026-12-31");
        assertThat(DistributionService.effectiveAt(i, LocalDate.parse("2026-01-01"))).isTrue();
        assertThat(DistributionService.effectiveAt(i, D)).isTrue();
        assertThat(DistributionService.effectiveAt(i, LocalDate.parse("2026-12-31"))).isFalse();
        assertThat(DistributionService.effectiveAt(i, LocalDate.parse("2025-12-31"))).isFalse();
    }

    @Test
    void open_left_bound() {
        assertThat(DistributionService.effectiveAt(item(null, "2026-06-10"), LocalDate.parse("2020-01-01")))
                .isTrue();
    }

    @Test
    void open_right_bound() {
        assertThat(DistributionService.effectiveAt(item("2026-01-01", null), LocalDate.parse("2030-01-01")))
                .isTrue();
    }
}
