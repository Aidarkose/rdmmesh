package bank.rdmmesh.authoring.internal.relational;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Чистый генератор DDL для relational store: {@code CREATE TABLE} физической таблицы
 * справочника по списку ключевых и атрибутивных колонок.
 *
 * <p>Идентификаторы (имена таблиц/колонок) валидируются против snake_case-паттерна —
 * того же, что в spec для key-part'ов/атрибутов/имён CodeSet'а — поэтому SQL-инъекция
 * через имена невозможна; значения строк всегда биндятся параметрами (см.
 * {@code RelationalStoreService}).
 */
public final class RelationalDdlBuilder {

    /** snake_case identifier, ≤64 символов (как в spec key-spec/code-set). */
    public static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** Базовое имя {@code <domain>__<codeset>}, ≤54 — чтобы "<base>__current" укладывалось в 63. */
    public static final Pattern BASE_TABLE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,53}$");

    /** Итоговое имя физической таблицы (draft/current), ≤63 — лимит идентификатора PG. */
    public static final Pattern TABLE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    public static final String DRAFT_SUFFIX = "__draft";
    public static final String CURRENT_SUFFIX = "__current";
    /** История всех PUBLISHED-версий (≤9 символов, чтобы укладываться в 63 при base≤54). */
    public static final String HISTORY_SUFFIX = "__history";

    /** Колонка version_id draft-таблицы (часть PK черновика). */
    public static final Column VERSION_ID = new Column("version_id", "uuid", true);

    /**
     * Стандартные колонки, которые есть у каждой материализованной таблицы. {@code parent_key}
     * хранится как {@code jsonb}-массив key-part'ов (без FK на себя — это Stage 4/6);
     * {@code order_index} — для детерминированной сортировки на read-path (Stage 3).
     */
    private static final List<Column> STANDARD = List.of(
            new Column("label_ru", "text", false),
            new Column("label_en", "text", false),
            new Column("description_ru", "text", false),
            new Column("description_en", "text", false),
            new Column("parent_key", "jsonb", false),
            new Column("parent_ref", "jsonb", false),
            new Column("order_index", "integer", false),
            new Column("status", "text", true, "'ACTIVE'"),
            new Column("effective_from", "date", false),
            new Column("effective_to", "date", false),
            // Bitemporal system-time envelope (Stage 4-full), зеркало code_item.system_*.
            new Column("system_from", "timestamptz", false),
            new Column("system_to", "timestamptz", false),
            // Операционные колонки (Stage 7c write-flip): стабильный id строки для id-based
            // API и row_version для optimistic-lock. В content_hash/diff НЕ участвуют.
            new Column("id", "uuid", false),
            new Column("row_version", "integer", true, "0"));

    private RelationalDdlBuilder() {}

    /** Колонка физической таблицы. */
    public record Column(String name, String sqlType, boolean notNull, String defaultExpr) {
        public Column(String name, String sqlType, boolean notNull) {
            this(name, sqlType, notNull, null);
        }
    }

    public static boolean isValidIdentifier(String name) {
        return name != null && IDENT.matcher(name).matches();
    }

    /** Имена стандартных колонок (label/description/parent_key/order_index/status/effective_*). */
    public static Set<String> standardColumnNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Column c : STANDARD) {
            names.add(c.name());
        }
        return names;
    }

    /** Базовое имя {@code <domain>__<codeset>} с проверкой длины/charset (≤54). */
    public static String tableName(String domainName, String codesetName) {
        require(isValidIdentifier(domainName), "domain name: " + domainName);
        require(isValidIdentifier(codesetName), "codeset name: " + codesetName);
        String name = domainName + "__" + codesetName;
        require(
                BASE_TABLE_IDENT.matcher(name).matches(),
                "derived base table name too long (>54): " + name);
        return name;
    }

    public static String draftTable(String baseName) {
        return baseName + DRAFT_SUFFIX;
    }

    public static String currentTable(String baseName) {
        return baseName + CURRENT_SUFFIX;
    }

    /** Таблица истории всех PUBLISHED-версий ({@code <base>__history}), PK как у draft. */
    public static String historyTable(String baseName) {
        return baseName + HISTORY_SUFFIX;
    }

    /** key+attr колонки + стандартные (label/status/effective), дедуп по имени, с сохранением порядка. */
    public static List<Column> withStandard(List<Column> keyAndAttr) {
        Set<String> seen = new LinkedHashSet<>();
        List<Column> all = new ArrayList<>();
        for (Column c : keyAndAttr == null ? List.<Column>of() : keyAndAttr) {
            validateColumn(c);
            if (seen.add(c.name())) {
                all.add(c);
            }
        }
        for (Column c : STANDARD) {
            if (seen.add(c.name())) {
                all.add(c);
            }
        }
        return all;
    }

    /**
     * {@code CREATE TABLE IF NOT EXISTS "schema"."table" (...)} с PK по ключевым колонкам.
     * Стандартные колонки добавляются, если их имена не заняты ключом/атрибутом.
     * Используется для {@code __current}-таблицы (PK = ключи справочника).
     */
    public static String createTable(
            String schema,
            String table,
            List<Column> keyColumns,
            List<Column> attributeColumns) {
        require(keyColumns != null && !keyColumns.isEmpty(), "at least one key column required");
        List<Column> all = withStandard(concat(keyColumns, attributeColumns));
        List<String> pk = new ArrayList<>();
        for (Column c : keyColumns) {
            pk.add(c.name());
        }
        return createTableWithPk(schema, table, all, pk);
    }

    /** Общий генератор: рендерит все колонки в заданном порядке + PRIMARY KEY (pkColumns). */
    public static String createTableWithPk(
            String schema, String table, List<Column> columns, List<String> pkColumns) {
        require(isValidIdentifier(schema), "schema: " + schema);
        require(TABLE_IDENT.matcher(table).matches(), "table name invalid/too long: " + table);
        require(columns != null && !columns.isEmpty(), "at least one column required");
        require(pkColumns != null && !pkColumns.isEmpty(), "at least one PK column required");

        Set<String> names = new LinkedHashSet<>();
        for (Column c : columns) {
            validateColumn(c);
            require(names.add(c.name()), "duplicate column: " + c.name());
        }
        for (String pk : pkColumns) {
            require(names.contains(pk), "PK column not among columns: " + pk);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ")
                .append(q(schema)).append('.').append(q(table)).append(" (\n");
        for (Column c : columns) {
            sb.append("    ").append(q(c.name())).append(' ').append(c.sqlType());
            if (c.notNull()) {
                sb.append(" NOT NULL");
            }
            if (c.defaultExpr() != null) {
                sb.append(" DEFAULT ").append(c.defaultExpr());
            }
            sb.append(",\n");
        }
        sb.append("    PRIMARY KEY (");
        for (int i = 0; i < pkColumns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(q(pkColumns.get(i)));
        }
        sb.append(")\n)");
        return sb.toString();
    }

    /**
     * Идемпотентная эволюция схемы (Stage 4-lite): {@code ALTER TABLE ... ADD COLUMN IF NOT
     * EXISTS} для каждой переданной колонки. {@code NOT NULL} при ALTER не навешиваем — на
     * непустой таблице добавление NOT-NULL-колонки без дефолта упадёт; целостность ключей
     * гарантирует исходный {@code CREATE}. {@code DEFAULT} переносим (нужен, напр., status).
     * Уже существующие колонки (ключи, ранее созданные) пропускаются {@code IF NOT EXISTS}.
     */
    public static String addColumnsIfNotExists(String schema, String table, List<Column> columns) {
        require(isValidIdentifier(schema), "schema: " + schema);
        require(TABLE_IDENT.matcher(table).matches(), "table name invalid/too long: " + table);
        require(columns != null && !columns.isEmpty(), "at least one column required");
        StringBuilder sb = new StringBuilder("ALTER TABLE ")
                .append(q(schema)).append('.').append(q(table)).append(' ');
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            validateColumn(c);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("ADD COLUMN IF NOT EXISTS ").append(q(c.name())).append(' ').append(c.sqlType());
            if (c.defaultExpr() != null) {
                sb.append(" DEFAULT ").append(c.defaultExpr());
            }
        }
        return sb.toString();
    }

    // ── иерархия: closure + cycle-detection по parent_key (Stage 4-full) ──────────

    /**
     * Рекурсивный CTE closure иерархии физической таблицы: пары
     * {@code (ancestor_key, descendant_key, depth)} по {@code parent_key} (jsonb-массив
     * key-part'ов). Аналог {@code authoring.code_item_closure}, но вычисляется on-demand
     * (без материализации). Self-reflexive (depth 0) + walk вверх; глубина ограничена 32
     * (как в V022) — на цикле рекурсия обрубается этим лимитом.
     *
     * <p>{@code self_key} строится как {@code jsonb_build_array(<ключевые колонки>)} — это
     * та же форма, что хранится в {@code parent_key} ребёнка, поэтому join сравнивает jsonb.
     */
    public static String closureQuery(
            String schema, String table, List<String> keyNames, boolean filterByVersion) {
        require(isValidIdentifier(schema), "schema: " + schema);
        require(TABLE_IDENT.matcher(table).matches(), "table name invalid/too long: " + table);
        String selfKey = jsonbBuildArray(keyNames);
        String from = q(schema) + '.' + q(table);
        String filter = filterByVersion ? " WHERE \"version_id\" = CAST(:v AS uuid)" : "";
        return """
            WITH RECURSIVE src AS (
                SELECT %1$s AS self_key, "parent_key" AS parent_key FROM %2$s%3$s
            ),
            walk AS (
                SELECT self_key AS ancestor_key, self_key AS descendant_key,
                       parent_key AS next_parent, 0 AS depth
                  FROM src
                UNION ALL
                SELECT p.self_key, w.descendant_key, p.parent_key, w.depth + 1
                  FROM walk w
                  JOIN src p ON p.self_key = w.next_parent
                 WHERE w.next_parent IS NOT NULL AND w.depth < 32
            )
            SELECT DISTINCT ancestor_key, descendant_key, depth FROM walk
            """.formatted(selfKey, from, filter);
    }

    /**
     * Рекурсивный CTE с нативным {@code CYCLE}-обнаружением (PG 14+): возвращает ключи
     * ({@code self_key} jsonb), участвующие в цикле {@code parent_key}. Демонстрация
     * cycle-detection на реляционной модели (вместо триггерной проверки V023).
     */
    public static String cycleDetectionQuery(
            String schema, String table, List<String> keyNames, boolean filterByVersion) {
        require(isValidIdentifier(schema), "schema: " + schema);
        require(TABLE_IDENT.matcher(table).matches(), "table name invalid/too long: " + table);
        String selfKey = jsonbBuildArray(keyNames);
        String from = q(schema) + '.' + q(table);
        String filter = filterByVersion ? " WHERE \"version_id\" = CAST(:v AS uuid)" : "";
        return """
            WITH RECURSIVE src AS (
                SELECT %1$s AS self_key, "parent_key" AS parent_key FROM %2$s%3$s
            ),
            walk AS (
                SELECT self_key, parent_key AS next_parent FROM src
                UNION ALL
                SELECT p.self_key, p.parent_key
                  FROM walk w
                  JOIN src p ON p.self_key = w.next_parent
                 WHERE w.next_parent IS NOT NULL
            ) CYCLE self_key SET is_cycle USING path
            SELECT DISTINCT self_key FROM walk WHERE is_cycle
            """.formatted(selfKey, from, filter);
    }

    // ── настоящие FK между __current-таблицами (Stage 6, E25 column_refs) ─────────

    /** Имя FK-констрейнта; при превышении лимита 63 — детерминированный хеш-суффикс. */
    public static String foreignKeyName(
            String fromTable, String fromColumn, String toTable, String toColumn) {
        String raw = "fk_" + fromTable + "__" + fromColumn + "__" + toColumn;
        if (raw.length() <= 63 && IDENT.matcher(raw).matches()) {
            return raw;
        }
        long h = Integer.toUnsignedLong((fromTable + '|' + fromColumn + '|' + toTable + '|' + toColumn).hashCode());
        return "fk_" + Long.toHexString(h);
    }

    /**
     * Идемпотентный {@code ALTER TABLE … DROP CONSTRAINT IF EXISTS …, ADD CONSTRAINT … FOREIGN
     * KEY (from) REFERENCES …(to)} (drop+add в одном statement — повторный вызов не падает).
     * Целевая колонка должна иметь unique/PK-констрейнт (это проверяет {@code RelationalStoreService}).
     */
    public static String addForeignKey(
            String schema, String fromTable, String fromColumn,
            String toTable, String toColumn, String constraintName) {
        require(isValidIdentifier(schema), "schema: " + schema);
        require(TABLE_IDENT.matcher(fromTable).matches(), "from table: " + fromTable);
        require(TABLE_IDENT.matcher(toTable).matches(), "to table: " + toTable);
        require(isValidIdentifier(fromColumn), "from column: " + fromColumn);
        require(isValidIdentifier(toColumn), "to column: " + toColumn);
        require(constraintName != null && IDENT.matcher(constraintName).matches(),
                "constraint name: " + constraintName);
        String from = q(schema) + '.' + q(fromTable);
        return "ALTER TABLE " + from
                + " DROP CONSTRAINT IF EXISTS " + q(constraintName) + ","
                + " ADD CONSTRAINT " + q(constraintName)
                + " FOREIGN KEY (" + q(fromColumn) + ')'
                + " REFERENCES " + q(schema) + '.' + q(toTable) + " (" + q(toColumn) + ')';
    }

    /** {@code jsonb_build_array("k1", "k2", ...)} из (провалидированных) ключевых колонок. */
    private static String jsonbBuildArray(List<String> keyNames) {
        require(keyNames != null && !keyNames.isEmpty(), "at least one key column required");
        StringBuilder sb = new StringBuilder("jsonb_build_array(");
        for (int i = 0; i < keyNames.size(); i++) {
            require(isValidIdentifier(keyNames.get(i)), "key column: " + keyNames.get(i));
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(q(keyNames.get(i)));
        }
        return sb.append(')').toString();
    }

    private static List<Column> concat(List<Column> a, List<Column> b) {
        List<Column> out = new ArrayList<>(a);
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }

    private static void validateColumn(Column c) {
        require(c != null, "null column");
        require(isValidIdentifier(c.name()), "column name: " + (c == null ? null : c.name()));
        // sqlType — из RelationalTypes (закрытый набор), но подстрахуемся.
        require(
                c.sqlType() != null && c.sqlType().matches("^[a-z][a-z ]{0,31}$"),
                "sql type: " + c.sqlType());
    }

    /** Двойные кавычки вокруг (уже провалидированного) идентификатора. */
    static String q(String ident) {
        return '"' + ident + '"';
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalArgumentException("relational DDL: " + msg);
        }
    }
}
