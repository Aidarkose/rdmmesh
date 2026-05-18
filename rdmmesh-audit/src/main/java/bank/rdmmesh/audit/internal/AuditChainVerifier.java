package bank.rdmmesh.audit.internal;

import java.util.List;

import bank.rdmmesh.audit.internal.dao.AuditLogDao.ChainRow;

/**
 * Pure-verifier hash-chain audit-журнала (E14 round 1).
 *
 * <p>Принимает упорядоченный по id ASC список строк {@link ChainRow} и
 * опциональный {@code anchorPrevHash} — hash, которым должна заканчиваться
 * предыдущая (вне range) запись. {@code anchorPrevHash=null} означает, что
 * пользователь верифицирует с начала журнала.
 *
 * <p>Проверки для каждой row в порядке id ASC:
 * <ol>
 *     <li><b>Continuity.</b> {@code row.prev_hash == expected_prev} (либо обе
 *         {@code null} для самой первой записи).</li>
 *     <li><b>Integrity.</b> {@code row.entry_hash == sha256(canonical_input)},
 *         где canonical_input собирается из {@code (prev_hash, event_id,
 *         event_type, payload_canonical, occurred_at)} — см.
 *         {@link AuditChainHasher#computeEntryHash}.</li>
 * </ol>
 *
 * <p>Первое же нарушение фиксируется в {@link Result#firstBrokenAt()} с
 * человеко-читаемой причиной. Дальнейшая верификация останавливается — нет
 * смысла лезть глубже, если корень цепочки уже разрушен.
 *
 * <p>Класс stateless, thread-safe; один {@link AuditChainHasher} переиспользуется
 * между вызовами.
 */
public final class AuditChainVerifier {

    private final AuditChainHasher hasher;

    public AuditChainVerifier(AuditChainHasher hasher) {
        this.hasher = hasher;
    }

    /**
     * @param rows           rows из {@code audit_log}, отсортированные по id ASC.
     * @param anchorPrevHash hash записи перед первой row в {@code rows}, либо
     *                       {@code null} если range начинается с самой первой
     *                       строки журнала.
     */
    public Result verify(List<ChainRow> rows, String anchorPrevHash) {
        if (rows.isEmpty()) {
            return new Result(true, 0, null, null, null, null);
        }

        String expectedPrev = anchorPrevHash;
        int checked = 0;
        for (ChainRow row : rows) {
            // 1. Continuity: row.prev_hash должен совпадать с entry_hash предыдущей.
            if (!equalsNullable(row.prevHash(), expectedPrev)) {
                return new Result(
                        false,
                        checked,
                        row.id(),
                        "prev_hash mismatch: expected="
                                + display(expectedPrev) + ", stored=" + display(row.prevHash()),
                        expectedPrev,
                        row.prevHash());
            }

            // 2. Integrity: пересчитанный entry_hash должен совпасть.
            String recomputed = hasher.computeEntryHash(
                    row.prevHash(),
                    row.eventId(),
                    row.eventType(),
                    row.payloadCanonical(),
                    row.occurredAt());
            if (!recomputed.equals(row.entryHash())) {
                return new Result(
                        false,
                        checked,
                        row.id(),
                        "entry_hash mismatch: recomputed="
                                + display(recomputed) + ", stored=" + display(row.entryHash()),
                        recomputed,
                        row.entryHash());
            }

            expectedPrev = row.entryHash();
            checked++;
        }

        return new Result(true, checked, null, null, null, null);
    }

    private static boolean equalsNullable(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private static String display(String hash) {
        return hash == null ? "<null>" : hash;
    }

    /**
     * Результат верификации.
     *
     * @param verified        true, если все проверенные записи прошли integrity + continuity.
     * @param checked         сколько записей фактически проверено (до first broken).
     * @param firstBrokenAt   id первой разрушенной записи, либо {@code null}.
     * @param reason          текстовое описание причины разрыва.
     * @param expectedHash    то, что должно было быть (recomputed либо expected prev).
     * @param storedHash      то, что лежит в БД.
     */
    public record Result(
            boolean verified,
            int checked,
            Long firstBrokenAt,
            String reason,
            String expectedHash,
            String storedHash) {}
}
