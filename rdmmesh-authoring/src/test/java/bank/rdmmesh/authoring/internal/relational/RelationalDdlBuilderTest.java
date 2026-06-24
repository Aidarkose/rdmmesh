package bank.rdmmesh.authoring.internal.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.authoring.internal.relational.RelationalDdlBuilder.Column;

class RelationalDdlBuilderTest {

    // ── RelationalTypes ──────────────────────────────────────────────────────────

    @Test
    void key_part_types_map_to_sql() {
        assertThat(RelationalTypes.keyPartSqlType("STRING")).isEqualTo("text");
        assertThat(RelationalTypes.keyPartSqlType("integer")).isEqualTo("bigint");
        assertThat(RelationalTypes.keyPartSqlType("NUMBER")).isEqualTo("double precision");
        assertThat(RelationalTypes.keyPartSqlType("BOOLEAN")).isEqualTo("boolean");
        assertThat(RelationalTypes.keyPartSqlType("DATE")).isEqualTo("date");
        assertThat(RelationalTypes.keyPartSqlType("DATETIME")).isEqualTo("timestamptz");
        assertThat(RelationalTypes.keyPartSqlType("UUID")).isEqualTo("uuid");
        assertThat(RelationalTypes.keyPartSqlType(null)).isEqualTo("text");
        assertThat(RelationalTypes.keyPartSqlType("weird")).isEqualTo("text");
    }

    @Test
    void json_schema_types_map_to_sql() {
        assertThat(RelationalTypes.jsonSchemaSqlType("integer", null, false)).isEqualTo("bigint");
        assertThat(RelationalTypes.jsonSchemaSqlType("number", null, false)).isEqualTo("double precision");
        assertThat(RelationalTypes.jsonSchemaSqlType("boolean", null, false)).isEqualTo("boolean");
        assertThat(RelationalTypes.jsonSchemaSqlType("string", "date", false)).isEqualTo("date");
        assertThat(RelationalTypes.jsonSchemaSqlType("string", "date-time", false)).isEqualTo("timestamptz");
        assertThat(RelationalTypes.jsonSchemaSqlType("string", "uuid", false)).isEqualTo("uuid");
        assertThat(RelationalTypes.jsonSchemaSqlType("string", null, false)).isEqualTo("text");
        assertThat(RelationalTypes.jsonSchemaSqlType("object", null, false)).isEqualTo("jsonb");
        // enum всегда text, даже если type=number
        assertThat(RelationalTypes.jsonSchemaSqlType("number", null, true)).isEqualTo("text");
    }

    // ── tableName ────────────────────────────────────────────────────────────────

    @Test
    void table_name_is_domain_double_underscore_codeset() {
        assertThat(RelationalDdlBuilder.tableName("r_branch", "r_ecl_branch_sgmnt"))
                .isEqualTo("r_branch__r_ecl_branch_sgmnt");
    }

    @Test
    void table_name_rejects_bad_identifiers_and_overlong() {
        assertThatThrownBy(() -> RelationalDdlBuilder.tableName("R_Branch", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RelationalDdlBuilder.tableName("a".repeat(40), "b".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }

    // ── createTable ──────────────────────────────────────────────────────────────

    @Test
    void create_table_has_typed_columns_pk_and_standard_columns() {
        String ddl = RelationalDdlBuilder.createTable(
                "rd_data",
                "r_branch__r_lnk_branch_to_ecl_sgmnt",
                List.of(new Column("branch_id", "text", true)),
                List.of(
                        new Column("branch_sgmnt_id", "bigint", false),
                        new Column("pd", "double precision", false)));

        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS \"rd_data\".\"r_branch__r_lnk_branch_to_ecl_sgmnt\"");
        assertThat(ddl).contains("\"branch_id\" text NOT NULL");
        assertThat(ddl).contains("\"branch_sgmnt_id\" bigint");
        assertThat(ddl).contains("\"pd\" double precision");
        // стандартные колонки
        assertThat(ddl).contains("\"status\" text NOT NULL DEFAULT 'ACTIVE'");
        assertThat(ddl).contains("\"effective_from\" date");
        // PK по ключу
        assertThat(ddl).contains("PRIMARY KEY (\"branch_id\")");
    }

    @Test
    void create_table_composite_pk() {
        String ddl = RelationalDdlBuilder.createTable(
                "rd_data",
                "credit__pd_matrix",
                List.of(
                        new Column("segment", "text", true),
                        new Column("rating", "text", true),
                        new Column("horizon", "text", true)),
                List.of());
        assertThat(ddl).contains("PRIMARY KEY (\"segment\", \"rating\", \"horizon\")");
    }

    @Test
    void attribute_colliding_with_standard_column_is_not_duplicated() {
        // атрибут с именем 'status' перекрывает стандартную колонку — она не дублируется
        String ddl = RelationalDdlBuilder.createTable(
                "rd_data",
                "d__c",
                List.of(new Column("code", "text", true)),
                List.of(new Column("status", "bigint", false)));
        assertThat(ddl.split("\"status\"", -1).length - 1).isEqualTo(1);
    }

    @Test
    void create_table_rejects_bad_column_identifier() {
        assertThatThrownBy(() -> RelationalDdlBuilder.createTable(
                        "rd_data", "d__c", List.of(new Column("Bad-Name", "text", true)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── модель C: draft/current ─────────────────────────────────────────────────

    @Test
    void draft_and_current_table_names() {
        String base = RelationalDdlBuilder.tableName("r_branch", "r_ecl_branch_sgmnt");
        assertThat(RelationalDdlBuilder.draftTable(base)).isEqualTo("r_branch__r_ecl_branch_sgmnt__draft");
        assertThat(RelationalDdlBuilder.currentTable(base)).isEqualTo("r_branch__r_ecl_branch_sgmnt__current");
    }

    @Test
    void history_table_name_and_fits_identifier_limit() {
        String base = RelationalDdlBuilder.tableName("r_branch", "r_ecl_branch_sgmnt");
        assertThat(RelationalDdlBuilder.historyTable(base))
                .isEqualTo("r_branch__r_ecl_branch_sgmnt__history");
        // base ≤54 + "__history"(9) укладывается в лимит 63 идентификатора PG.
        assertThat(RelationalDdlBuilder.historyTable("a".repeat(54))).hasSize(63);
    }

    @Test
    void draft_table_has_version_id_first_in_pk() {
        List<Column> data = RelationalDdlBuilder.withStandard(
                List.of(new Column("code", "text", true)));
        List<Column> draftCols = new java.util.ArrayList<>();
        draftCols.add(RelationalDdlBuilder.VERSION_ID);
        draftCols.addAll(data);
        String ddl = RelationalDdlBuilder.createTableWithPk(
                "rd_data", "d__c__draft", draftCols, List.of("version_id", "code"));
        assertThat(ddl).contains("\"version_id\" uuid NOT NULL");
        assertThat(ddl).contains("PRIMARY KEY (\"version_id\", \"code\")");
    }

    @Test
    void with_standard_dedupes_and_appends() {
        List<Column> data = RelationalDdlBuilder.withStandard(
                List.of(new Column("code", "text", true), new Column("status", "text", false)));
        // 'status' из ввода не дублируется стандартной колонкой 'status'
        long statusCount = data.stream().filter(c -> c.name().equals("status")).count();
        assertThat(statusCount).isEqualTo(1);
        // присутствуют стандартные label_ru/effective_from
        assertThat(data.stream().anyMatch(c -> c.name().equals("label_ru"))).isTrue();
        assertThat(data.stream().anyMatch(c -> c.name().equals("effective_from"))).isTrue();
    }

    // ── Stage 4-lite: новые стандартные колонки + эволюция схемы ──────────────────

    @Test
    void with_standard_includes_stage4lite_columns() {
        List<Column> data = RelationalDdlBuilder.withStandard(List.of(new Column("code", "text", true)));
        assertThat(data.stream().anyMatch(c -> c.name().equals("description_ru"))).isTrue();
        assertThat(data.stream().anyMatch(c -> c.name().equals("description_en"))).isTrue();
        assertThat(data.stream().anyMatch(c -> c.name().equals("order_index"))).isTrue();
        Column parentKey = data.stream().filter(c -> c.name().equals("parent_key")).findFirst().orElseThrow();
        assertThat(parentKey.sqlType()).isEqualTo("jsonb");
    }

    @Test
    void add_columns_if_not_exists_renders_idempotent_alter() {
        String sql = RelationalDdlBuilder.addColumnsIfNotExists(
                "rd_data",
                "d__c__current",
                List.of(
                        new Column("code", "text", true),
                        new Column("order_index", "integer", false),
                        new Column("status", "text", true, "'ACTIVE'")));
        assertThat(sql).startsWith("ALTER TABLE \"rd_data\".\"d__c__current\" ");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS \"code\" text");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS \"order_index\" integer");
        // DEFAULT переносится, NOT NULL при ALTER — нет (упал бы на непустой таблице)
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS \"status\" text DEFAULT 'ACTIVE'");
        assertThat(sql).doesNotContain("NOT NULL");
    }

    // ── Stage 4-full: closure + cycle-detection ──────────────────────────────────

    @Test
    void closure_query_builds_recursive_cte_over_jsonb_key() {
        String sql = RelationalDdlBuilder.closureQuery(
                "rd_data", "d__c__current", List.of("branch_id"), false);
        assertThat(sql).contains("WITH RECURSIVE");
        assertThat(sql).contains("jsonb_build_array(\"branch_id\")");
        assertThat(sql).contains("FROM \"rd_data\".\"d__c__current\"");
        assertThat(sql).contains("w.depth < 32");
        assertThat(sql).contains("ancestor_key").contains("descendant_key").contains("depth");
        assertThat(sql).doesNotContain("version_id"); // current без фильтра
    }

    @Test
    void closure_query_composite_key_and_version_filter() {
        String sql = RelationalDdlBuilder.closureQuery(
                "rd_data", "d__c__draft", List.of("seg", "rating"), true);
        assertThat(sql).contains("jsonb_build_array(\"seg\", \"rating\")");
        assertThat(sql).contains("WHERE \"version_id\" = CAST(:v AS uuid)");
    }

    @Test
    void cycle_detection_query_uses_native_cycle_clause() {
        String sql = RelationalDdlBuilder.cycleDetectionQuery(
                "rd_data", "d__c__current", List.of("code"), false);
        assertThat(sql).contains("CYCLE self_key SET is_cycle USING path");
        assertThat(sql).contains("WHERE is_cycle");
        assertThat(sql).contains("jsonb_build_array(\"code\")");
    }

    @Test
    void hierarchy_queries_reject_bad_key_columns() {
        assertThatThrownBy(() -> RelationalDdlBuilder.closureQuery(
                        "rd_data", "d__c", List.of("Bad-Name"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RelationalDdlBuilder.cycleDetectionQuery(
                        "rd_data", "d__c", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Stage 6: настоящие FK ─────────────────────────────────────────────────────

    @Test
    void foreign_key_name_readable_when_short() {
        assertThat(RelationalDdlBuilder.foreignKeyName(
                "d__c__current", "branch_sgmnt_id", "d__t__current", "id"))
                .isEqualTo("fk_d__c__current__branch_sgmnt_id__id");
    }

    @Test
    void foreign_key_name_hashes_when_too_long() {
        String name = RelationalDdlBuilder.foreignKeyName(
                "a".repeat(60), "branch_sgmnt_id", "t__current", "id");
        assertThat(name).matches("fk_[0-9a-f]+").hasSizeLessThanOrEqualTo(63);
    }

    @Test
    void add_foreign_key_is_idempotent_drop_then_add() {
        String sql = RelationalDdlBuilder.addForeignKey(
                "rd_data", "d__c__current", "branch_sgmnt_id", "d__t__current", "id", "fk_x");
        assertThat(sql).contains("ALTER TABLE \"rd_data\".\"d__c__current\"");
        assertThat(sql).contains("DROP CONSTRAINT IF EXISTS \"fk_x\"");
        assertThat(sql).contains("ADD CONSTRAINT \"fk_x\" FOREIGN KEY (\"branch_sgmnt_id\")");
        assertThat(sql).contains("REFERENCES \"rd_data\".\"d__t__current\" (\"id\")");
    }

    @Test
    void add_foreign_key_rejects_bad_identifiers() {
        assertThatThrownBy(() -> RelationalDdlBuilder.addForeignKey(
                        "rd_data", "d__c__current", "Bad-Name", "d__t__current", "id", "fk_x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
