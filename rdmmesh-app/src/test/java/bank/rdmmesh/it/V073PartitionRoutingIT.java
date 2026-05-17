package bank.rdmmesh.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * E14 round 9 (backlog §3 #3) — переводит ручной V073-smoke (E14.7 §5) в
 * автотест на реальном Postgres: routing INSERT'а по {@code occurred_at},
 * идемпотентность {@code ensure_audit_partition}, retention-guard
 * {@code drop_audit_partition_if_archived}.
 *
 * <p>Всё под superuser-соединением: ops-функции {@code REVOKE EXECUTE FROM
 * PUBLIC} (V073), суперюзер обходит REVOKE; INSERT в append-only-таблицу
 * разрешён (триггеры — только UPDATE/DELETE/TRUNCATE).
 */
final class V073PartitionRoutingIT extends PostgresIT {

    private static final String HASH64 = "a".repeat(64); // удовлетворяет CHECK ^[a-f0-9]{64}$

    /** Минимальный валидный INSERT; возвращает партицию, куда строка попала. */
    private static String insertAt(Connection c, String occurredAtIso) throws SQLException {
        UUID eventId = UUID.randomUUID();
        try (Statement st = c.createStatement()) {
            st.execute(
                    "INSERT INTO audit.audit_log "
                            + "(event_id, event_type, occurred_at, payload, payload_canonical, entry_hash) "
                            + "VALUES ('" + eventId + "', 'IT_EVENT', '" + occurredAtIso + "'::timestamptz, "
                            + "'{}'::jsonb, '{}', '" + HASH64 + "')");
            try (ResultSet rs = st.executeQuery(
                    "SELECT tableoid::regclass::text FROM audit.audit_log "
                            + "WHERE event_id='" + eventId + "'")) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static boolean partitionExists(Connection c, String part) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT 1 FROM pg_class cl JOIN pg_namespace n ON n.oid=cl.relnamespace "
                                + "WHERE n.nspname='audit' AND cl.relname='" + part + "'")) {
            return rs.next();
        }
    }

    @Test
    void currentMonthRowGoesToMonthlyPartition_oldDateGoesToDefault() throws SQLException {
        try (Connection c = adminConnection()) {
            // На свежей миграции созданы помесячные партиции на текущий+следующий
            // месяц (v_min=v_max=now(), цикл до v_max+1мес) + DEFAULT.
            String nowPart = insertAt(c, "now()");
            assertThat(nowPart)
                    .as("строка с occurred_at=now() → помесячная партиция, не DEFAULT")
                    .matches("audit\\.audit_log_y\\d{4}m\\d{2}")
                    .isNotEqualTo("audit.audit_log_default");

            // Древняя дата — помесячной партиции нет → DEFAULT-safety-net.
            String oldPart = insertAt(c, "2000-01-15T00:00:00Z");
            assertThat(oldPart).isEqualTo("audit.audit_log_default");
        }
    }

    @Test
    void ensureAuditPartitionIsIdempotent() throws SQLException {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            // Месяц без существующей партиции.
            String p1;
            try (ResultSet rs = st.executeQuery(
                    "SELECT audit.ensure_audit_partition(DATE '2031-03-15')")) {
                rs.next();
                p1 = rs.getString(1);
            }
            // Повторный вызов того же месяца — no-op, тот же результат, без ошибки.
            String p2;
            try (ResultSet rs = st.executeQuery(
                    "SELECT audit.ensure_audit_partition(DATE '2031-03-01')")) {
                rs.next();
                p2 = rs.getString(1);
            }
            assertThat(p1).isEqualTo("audit_log_y2031m03").isEqualTo(p2);
            assertThat(partitionExists(c, "audit_log_y2031m03")).isTrue();
        }
    }

    @Test
    void retentionGuardRejectsUnarchived() throws SQLException {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute("SELECT audit.ensure_audit_partition(DATE '2031-04-01')");
            assertThatThrownBy(
                            () -> st.execute(
                                    "SELECT audit.drop_audit_partition_if_archived("
                                            + "'audit_log_y2031m04', false)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("только после immutable-архива");
        }
    }

    @Test
    void retentionGuardRejectsWithinRetentionWindow() throws SQLException {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            // Партиция на следующий год — заведомо внутри 10-летнего окна,
            // даже с p_archived=true дроп запрещён (разорвёт chain-сегмент).
            st.execute("SELECT audit.ensure_audit_partition((now() + interval '1 year')::date)");
            String part;
            try (ResultSet rs = st.executeQuery(
                    "SELECT audit.ensure_audit_partition((now() + interval '1 year')::date)")) {
                rs.next();
                part = rs.getString(1);
            }
            final String p = part;
            assertThatThrownBy(
                            () -> st.execute(
                                    "SELECT audit.drop_audit_partition_if_archived('" + p + "', true)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("внутри retention-окна");
        }
    }

    @Test
    void retentionGuardRejectsDefaultAndUnknown() throws SQLException {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            assertThatThrownBy(
                            () -> st.execute(
                                    "SELECT audit.drop_audit_partition_if_archived("
                                            + "'audit_log_default', true)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("DEFAULT-партицию дропать нельзя");

            assertThatThrownBy(
                            () -> st.execute(
                                    "SELECT audit.drop_audit_partition_if_archived("
                                            + "'audit_log_y1900m01', true)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("не найдена");
        }
    }

    @Test
    void retentionGuardDropsArchivedPartitionOutsideWindow() throws SQLException {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            // Древний месяц: верхняя граница 2005-07-01 < now()-10y → дроп разрешён.
            st.execute("SELECT audit.ensure_audit_partition(DATE '2005-06-15')");
            assertThat(partitionExists(c, "audit_log_y2005m06")).isTrue();

            st.execute(
                    "SELECT audit.drop_audit_partition_if_archived('audit_log_y2005m06', true)");

            assertThat(partitionExists(c, "audit_log_y2005m06"))
                    .as("архивированная партиция вне retention — дропнута")
                    .isFalse();
        }
    }
}
