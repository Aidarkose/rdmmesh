package bank.rdmmesh.audit.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.audit.internal.dao.AuditLogDao.ChainRow;

import static org.assertj.core.api.Assertions.assertThat;

final class AuditChainVerifierTest {

    private final AuditChainHasher hasher = new AuditChainHasher(AuditChainHasher.defaultMapper());
    private final AuditChainVerifier verifier = new AuditChainVerifier(hasher);

    @Test
    void empty_chain_is_trivially_verified() {
        AuditChainVerifier.Result r = verifier.verify(List.of(), null);

        assertThat(r.verified()).isTrue();
        assertThat(r.checked()).isZero();
        assertThat(r.firstBrokenAt()).isNull();
    }

    @Test
    void valid_chain_of_three_passes() {
        List<ChainRow> rows = synthesise(3, null);

        AuditChainVerifier.Result r = verifier.verify(rows, null);

        assertThat(r.verified()).isTrue();
        assertThat(r.checked()).isEqualTo(3);
        assertThat(r.firstBrokenAt()).isNull();
    }

    @Test
    void tampered_payload_detected_as_entry_hash_mismatch() {
        List<ChainRow> rows = synthesise(3, null);
        // Подменяем payload среднего row'а, не пересчитывая entry_hash —
        // имитируем атаку «изменили payload, забыли пересчитать chain».
        ChainRow middle = rows.get(1);
        ChainRow tampered = new ChainRow(
                middle.id(), middle.eventId(), middle.eventType(),
                middle.occurredAt(), "{\"hacked\":true}",
                middle.prevHash(), middle.entryHash());
        rows.set(1, tampered);

        AuditChainVerifier.Result r = verifier.verify(rows, null);

        assertThat(r.verified()).isFalse();
        assertThat(r.firstBrokenAt()).isEqualTo(2L);
        assertThat(r.reason()).contains("entry_hash mismatch");
        assertThat(r.checked()).isEqualTo(1); // первая запись прошла, разрыв на второй
    }

    @Test
    void tampered_prev_hash_detected_as_continuity_break() {
        List<ChainRow> rows = synthesise(3, null);
        ChainRow middle = rows.get(1);
        // Подменяем prev_hash, но оставляем entry_hash оригинальным — это
        // continuity break: row.prev_hash !== entry_hash предыдущей.
        ChainRow tampered = new ChainRow(
                middle.id(), middle.eventId(), middle.eventType(),
                middle.occurredAt(), middle.payloadCanonical(),
                "0".repeat(64), middle.entryHash());
        rows.set(1, tampered);

        AuditChainVerifier.Result r = verifier.verify(rows, null);

        assertThat(r.verified()).isFalse();
        assertThat(r.firstBrokenAt()).isEqualTo(2L);
        assertThat(r.reason()).contains("prev_hash mismatch");
    }

    @Test
    void anchor_prev_hash_must_match_first_row_prev() {
        // Range [10..12] — anchor должен быть entry_hash row'а id=9. Если anchor
        // не подаётся в Result, или подаётся неверный — это continuity break.
        List<ChainRow> rows = synthesise(3, "anchor_prev_hash");

        AuditChainVerifier.Result r1 = verifier.verify(rows, "anchor_prev_hash");
        assertThat(r1.verified()).isTrue();

        AuditChainVerifier.Result r2 = verifier.verify(rows, "wrong_anchor");
        assertThat(r2.verified()).isFalse();
        assertThat(r2.firstBrokenAt()).isEqualTo(rows.get(0).id());
        assertThat(r2.reason()).contains("prev_hash mismatch");
    }

    @Test
    void verifier_stops_at_first_break_not_cascades() {
        // 5 строк: ломаем 3-ю. Verifier должен остановиться, не пытаясь
        // продолжать (последующие записи унаследуют разрушенный chain).
        List<ChainRow> rows = synthesise(5, null);
        ChainRow third = rows.get(2);
        ChainRow tampered = new ChainRow(
                third.id(), third.eventId(), third.eventType(),
                third.occurredAt(), "{\"tampered\":1}",
                third.prevHash(), third.entryHash());
        rows.set(2, tampered);

        AuditChainVerifier.Result r = verifier.verify(rows, null);

        assertThat(r.verified()).isFalse();
        assertThat(r.firstBrokenAt()).isEqualTo(3L);
        assertThat(r.checked()).isEqualTo(2);  // первые две прошли
    }

    @Test
    void first_row_with_null_prev_hash_is_valid_anchor() {
        // Самая первая запись журнала: prev_hash=null. Verifier на anchor=null
        // должен это принять.
        List<ChainRow> rows = synthesise(1, null);
        assertThat(rows.get(0).prevHash()).isNull();

        AuditChainVerifier.Result r = verifier.verify(rows, null);

        assertThat(r.verified()).isTrue();
    }

    /**
     * Строит синтетическую chain из {@code count} записей с корректными
     * prev_hash/entry_hash. Первая row имеет prev_hash={@code initialPrev}
     * (если null — это самая первая запись журнала).
     */
    private List<ChainRow> synthesise(int count, String initialPrev) {
        List<ChainRow> result = new ArrayList<>(count);
        String prev = initialPrev;
        for (int i = 0; i < count; i++) {
            UUID eventId = UUID.fromString(String.format(
                    "00000000-0000-0000-0000-%012d", i + 1));
            OffsetDateTime ts = OffsetDateTime.of(
                    2026, 5, 12, 10, 0, i, 0, ZoneOffset.UTC);
            String payload = "{\"i\":" + i + "}";
            String eventType = "WORKFLOW_TRANSITION";
            String entryHash = hasher.computeEntryHash(prev, eventId, eventType, payload, ts);
            result.add(new ChainRow(
                    i + 1L, eventId, eventType, ts, payload, prev, entryHash));
            prev = entryHash;
        }
        return result;
    }
}
