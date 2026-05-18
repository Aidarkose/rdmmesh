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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * База для интеграционных тестов (E14 round 9; round 14 — singleton-fix).
 *
 * <p><b>JVM-singleton-контейнер.</b> Один Postgres на ВСЮ JVM, стартует в
 * static-инициализаторе и не останавливается явно (Ryuk/JVM-exit уберёт).
 * НЕ {@code @Container}: extension {@code @Testcontainers} останавливал бы
 * контейнер после КАЖДОГО тест-класса, а {@code PG} — общее static-поле
 * базы → следующий класс получал бы пустой Postgres, но {@code migrateOnce}
 * пропускался бы по guard'у (баг round-14 CI: «schema audit does not exist»,
 * «password authentication failed for rdmmesh_app»). Singleton + guard =
 * миграция один раз, БД переживает все классы.
 *
 * <p><b>Graceful skip без Docker.</b> {@code @Testcontainers(
 * disabledWithoutDocker=true)} дизейблит класс, если Docker-API недоступен
 * (Docker-Desktop/WSL2 + dockerized-bin/mvn, E14.9 §2). Static-init стартует
 * контейнер ТОЛЬКО при {@code DockerClientFactory.isDockerAvailable()} (тот
 * же признак, что у условия extension'а) — иначе {@code PG=null}, тесты
 * скипаются и null не разыменовывается. На CI (нативный dockerd) —
 * контейнер реально поднимается, IT исполняются и гейтят merge.
 *
 * <p>Flyway идёт под прод-ролью {@code rdmmesh_app} (паритет с prod,
 * E14.7 §5). БД общая на все IT — каждый IT обязан скоупить свои данные
 * (уникальные id/месяцы/имена), не полагаясь на пустую БД.
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

    /** JVM-singleton: стартует один раз, extension'ом НЕ управляется. */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> PG;

    static {
        PostgreSQLContainer<?> c = null;
        // Тот же признак, что у @Testcontainers(disabledWithoutDocker): нет
        // Docker → контейнер не стартуем, extension скипнет класс, PG=null
        // не разыменуется (тесты не выполняются).
        if (DockerClientFactory.instance().isDockerAvailable()) {
            c = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_PASS);
            c.start();
        }
        PG = c;
    }

    private static MigrateResult migrateResult;
    private static boolean migrated;

    /** Один раз на JVM (singleton-контейнер): прод-роль + реальный Flyway. */
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

    /** Под rdmmesh_app, с теми же плагинами, что RdmmeshApplication. */
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

    /** JDBC-координаты под rdmmesh_app — для движков с собственным пулом (Flowable). */
    protected static String appJdbcUrl() {
        return PG.getJdbcUrl();
    }

    protected static String appUser() {
        return APP_USER;
    }

    protected static String appPassword() {
        return APP_PASS;
    }
}
