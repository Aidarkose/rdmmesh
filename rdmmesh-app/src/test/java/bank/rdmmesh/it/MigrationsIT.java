package bank.rdmmesh.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Реальный Flyway-путь под прод-ролью {@code rdmmesh_app} (E14 round 9 —
 * закрывает E14.7 §3 #4: «нет автоматической проверки V073/реального
 * Flyway-пути»). Раньше это проверялось только ручным {@code docker compose}
 * smoke'ом.
 */
final class MigrationsIT extends PostgresIT {

    @Test
    void allMigrationsApplyToV073() {
        assertThat(migrateResult().targetSchemaVersion).isEqualTo("073");
        assertThat(migrateResult().migrationsExecuted).isEqualTo(17);
    }

    @Test
    void allEightSchemasExist() throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Connection c = adminConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT schema_name FROM information_schema.schemata")) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        assertThat(schemas)
                .contains(
                        "rdmmesh_meta",
                        "catalog",
                        "authoring",
                        "workflow",
                        "publishing",
                        "identity",
                        "ownership",
                        "audit");
    }

    @Test
    void auditLogIsRangePartitioned() throws SQLException {
        try (Connection c = adminConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT relkind FROM pg_class WHERE oid = 'audit.audit_log'::regclass")) {
            assertThat(rs.next()).isTrue();
            // 'p' = partitioned table (V073 RANGE по occurred_at)
            assertThat(rs.getString(1)).isEqualTo("p");
        }
    }

    @Test
    void appRoleCannotMutateAuditLog() throws SQLException {
        // V070 append-only: REVOKE UPDATE,DELETE,TRUNCATE FROM rdmmesh_app.
        try (Connection c = appConnection(); Statement st = c.createStatement()) {
            assertThatThrownBy(
                            () -> st.execute(
                                    "UPDATE audit.audit_log SET event_type='X' WHERE id IS NOT NULL"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }
}
