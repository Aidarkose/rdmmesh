package bank.rdmmesh.app;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Top-level Dropwizard configuration. Reads database, flyway, keycloak, openmetadata
 * blocks from {@code config.yml}; environment substitution is enabled in
 * {@link RdmmeshApplication#initialize}.
 */
public final class RdmmeshConfiguration extends Configuration {

    @Valid
    @NotNull
    @JsonProperty("database")
    private DataSourceFactory database = new DataSourceFactory();

    @Valid
    @NotNull
    @JsonProperty("flyway")
    private FlywayConfig flyway = new FlywayConfig();

    @Valid
    @NotNull
    @JsonProperty("keycloak")
    private KeycloakConfig keycloak = new KeycloakConfig();

    @Valid
    @NotNull
    @JsonProperty("openmetadata")
    private OpenMetadataConfig openmetadata = new OpenMetadataConfig();

    @Valid
    @NotNull
    @JsonProperty("workflow")
    private WorkflowConfig workflow = new WorkflowConfig();

    public DataSourceFactory getDatabase() {
        return database;
    }

    public FlywayConfig getFlyway() {
        return flyway;
    }

    public KeycloakConfig getKeycloak() {
        return keycloak;
    }

    public OpenMetadataConfig getOpenmetadata() {
        return openmetadata;
    }

    public WorkflowConfig getWorkflow() {
        return workflow;
    }

    /**
     * Выбор движка workflow (V2 / BR-18, ADR-009). {@code enum} — дефолтная
     * enum-StateMachine (поведение пилота 1:1, нулевой риск); {@code flowable}
     * — in-process BPMN-движок Flowable за тем же {@code WorkflowPort}.
     * Обратимо рестартом с другим {@code RDM_WORKFLOW_ENGINE}.
     */
    public static final class WorkflowConfig {

        @JsonProperty("engine")
        @NotNull
        private String engine = "enum";

        public String getEngine() {
            return engine;
        }

        public boolean isFlowable() {
            return "flowable".equalsIgnoreCase(engine.trim());
        }
    }

    /** Flyway-specific knobs that are not in the upstream {@code DataSourceFactory}. */
    public static final class FlywayConfig {

        /**
         * Whether the application should run {@code flyway migrate} on startup. Dev
         * convenience; production deploys should set this {@code false} and run a
         * dedicated migrate step out of band.
         */
        @JsonProperty("autoMigrate")
        private boolean autoMigrate = true;

        /**
         * Classpath / filesystem locations holding versioned SQL. Default order matches
         * the cross-module sequence V001…V070.
         */
        @JsonProperty("locations")
        @NotNull
        private List<String> locations = List.of(
                "classpath:db/migration/_init",
                "classpath:db/migration/catalog",
                "classpath:db/migration/authoring",
                "classpath:db/migration/workflow",
                "classpath:db/migration/publishing",
                "classpath:db/migration/identity",
                "classpath:db/migration/ownership",
                "classpath:db/migration/audit",
                // E18 (ADR-0010): admin-bounded context (resolution_task + future).
                "classpath:db/migration/admin");

        /** All schemas Flyway is allowed to manage (created if missing). */
        @JsonProperty("schemas")
        @NotNull
        private List<String> schemas = List.of(
                "catalog", "authoring", "workflow", "publishing", "identity", "ownership", "audit", "admin");

        /** Schema that hosts {@code flyway_schema_history}. */
        @JsonProperty("defaultSchema")
        @NotNull
        private String defaultSchema = "rdmmesh_meta";

        public boolean isAutoMigrate() {
            return autoMigrate;
        }

        public List<String> getLocations() {
            return locations;
        }

        public List<String> getSchemas() {
            return schemas;
        }

        public String getDefaultSchema() {
            return defaultSchema;
        }
    }
}
