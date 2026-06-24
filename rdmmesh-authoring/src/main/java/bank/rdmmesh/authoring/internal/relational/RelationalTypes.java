package bank.rdmmesh.authoring.internal.relational;

/**
 * Маппинг логических типов справочника → физические типы PostgreSQL для relational store.
 *
 * <p>Чистые статические функции — без зависимостей от Jdbi/JDBC, легко тестируются.
 * Зеркалит логику OM-коннектора (om-rdmmesh-source/mapping.py), но целевой таблицей
 * выступает реальная Postgres-таблица, а не OM Column.
 */
public final class RelationalTypes {

    private RelationalTypes() {}

    /** {@code key_spec.parts[].type} (STRING/INTEGER/…) → SQL-тип. */
    public static String keyPartSqlType(String keyPartType) {
        String t = keyPartType == null ? "STRING" : keyPartType.trim().toUpperCase();
        return switch (t) {
            case "INTEGER" -> "bigint";
            case "NUMBER" -> "double precision";
            case "BOOLEAN" -> "boolean";
            case "DATE" -> "date";
            case "DATETIME" -> "timestamptz";
            case "UUID" -> "uuid";
            default -> "text"; // STRING + неизвестное
        };
    }

    /**
     * JSON Schema property → SQL-тип. {@code jsonType} — строковый "type"
     * (или первый не-null элемент union'а), {@code format} — необязательный формат,
     * {@code hasEnum} — есть ли {@code enum} (enum всегда хранится как text).
     */
    public static String jsonSchemaSqlType(String jsonType, String format, boolean hasEnum) {
        if (hasEnum) {
            return "text";
        }
        String t = jsonType == null ? "string" : jsonType.trim().toLowerCase();
        return switch (t) {
            case "integer" -> "bigint";
            case "number" -> "double precision";
            case "boolean" -> "boolean";
            case "object", "array" -> "jsonb"; // вложенные структуры — деградируем в jsonb
            case "string" -> stringSqlType(format);
            default -> "text";
        };
    }

    private static String stringSqlType(String format) {
        if (format == null) {
            return "text";
        }
        return switch (format.trim().toLowerCase()) {
            case "date" -> "date";
            case "date-time", "datetime" -> "timestamptz";
            case "uuid" -> "uuid";
            default -> "text";
        };
    }
}
