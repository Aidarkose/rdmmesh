package bank.rdmmesh.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Базовый класс для интеграционных тестов (E14 round 9). Поднимает один
 * Postgres-контейнер на класс, создаёт прод-идентичную роль
 * {@code rdmmesh_app} и прогоняет <b>реальный</b> Flyway-путь под этой ролью —
 * <em>ровно как в проде</em> (RDM_DB_USER=rdmmesh_app, см. compose +
 * E14.7 §5: миграции под rdmmesh_app, не под суперюзером — это вскрывало
 * round-1 дефекты, дожившие до round 7).
 *
 * <p>Локации/схемы/defaultSchema совпадают с {@code config.yml} flyway-блоком
 * и {@code RdmmeshApplication.runFlyway}. Subclass'ы получают
 * {@link #migrateResult()}, {@link #appConnection()} (под rdmmesh_app — для
 * проверок append-only/permission denied) и {@link #adminConnection()}
 * (суперюзер — setup/tamper).
 *
 * <p><b>{@code disabledWithoutDocker = true}.</b> Если Docker-API недоступен
 * (напр. dockerized-Maven поверх Docker-Desktop/WSL2, где bind-mount
 * docker.sock = cli-прокси с desktop-stub /info → 400), весь класс
 * <em>скипается</em>, а не падает — `verify` остаётся зелёным локально, а
 * на CI/обычном dockerd IT гоняется по-настоящему. Это идиоматичный
 * Testcontainers-механизм ровно для такого окружения (handoff E14.9 §3).
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIT {

    private static final String ADMIN_USER = "rdmmesh_admin";
    private static final String ADMIN_PASS = "rdmmesh_admin_dev";
    private static final String APP_USER = "rdmmesh_app";
    private static final String APP_PASS = "rdmmesh_dev";
    private static final String DB = "rdmmesh";

    private static final List<String> FLYWAY_LOCATIONS = List.of(
            "classpath:db/migration/_init",
            "classpath:db/migration/catalog",
            "classpath:db/migration/authoring",
            "classpath:db/migration/workflow",
            "classpath:db/migration/publishing",
            "classpath:db/migration/identity",
            "classpath:db/migration/ownership",
            "classpath:db/migration/audit");
    private static final List<String> FLYWAY_SCHEMAS = List.of(
            "catalog", "authoring", "workflow", "publishing", "identity", "ownership", "audit");
    private static final String DEFAULT_SCHEMA = "rdmmesh_meta";

    @Container
    @SuppressWarnings("resource") // lifecycle управляет @Testcontainers-extension
    private static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_PASS);

    private static MigrateResult migrateResult;
    private static boolean migrated;

    /** Один раз на класс: прод-роль + реальный Flyway под rdmmesh_app. */
    @BeforeAll
    static synchronized void migrateOnce() throws SQLException {
        if (migrated) {
            return;
        }
        bootstrapAppRole();
        migrateResult = runFlyway();
        migrated = true;
    }

    /** Прод-идентичная роль: mirror docker/postgres/init/00-create-app-role.sql. */
    private static void bootstrapAppRole() throws SQLException {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute(
                    "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='"
                            + APP_USER + "') THEN CREATE ROLE " + APP_USER
                            + " WITH LOGIN PASSWORD '" + APP_PASS + "'; END IF; END $$;");
            st.execute("GRANT CONNECT ON DATABASE " + DB + " TO " + APP_USER);
            st.execute("GRANT CREATE ON DATABASE " + DB + " TO " + APP_USER);
        }
    }

    /** Те же опции, что RdmmeshApplication.runFlyway, под rdmmesh_app. */
    private static MigrateResult runFlyway() {
        return Flyway.configure()
                .dataSource(PG.getJdbcUrl(), APP_USER, APP_PASS)
                .locations(FLYWAY_LOCATIONS.toArray(String[]::new))
                .schemas(FLYWAY_SCHEMAS.toArray(String[]::new))
                .defaultSchema(DEFAULT_SCHEMA)
                .createSchemas(true)
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .load()
                .migrate();
    }

    protected static MigrateResult migrateResult() {
        return migrateResult;
    }

    /** Под rdmmesh_app, с теми же плагинами, что RdmmeshApplication (SqlObject + Postgres). */
    protected static Jdbi appJdbi() {
        return Jdbi.create(PG.getJdbcUrl(), APP_USER, APP_PASS)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin());
    }

    protected static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), APP_USER, APP_PASS);
    }

    protected static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), ADMIN_USER, ADMIN_PASS);
    }
}
