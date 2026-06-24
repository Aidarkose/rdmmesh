package bank.rdmmesh.api.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Узкий read-only интерфейс catalog'а для соседних bounded contexts. Через него
 * {@code rdmmesh-authoring} (и позже {@code publishing} / {@code distribution})
 * получают snapshot CodeSet'а и активной CodeSetSchema без cross-module-импортов
 * (ArchUnit-правило {@code modules_do_not_depend_on_each_other}).
 *
 * <p>«Read» здесь — это про read-side контракт catalog'а, а не про read-replica.
 * Реализация живёт в catalog-модуле и проксирует {@code CatalogService}.
 */
public interface CatalogReadPort {

    /** Минимальный snapshot CodeSet'а, нужный авторингу для валидации и draft-операций. */
    Optional<CodeSetSnapshot> findCodeSet(UUID codesetId);

    /**
     * Активная (current) CodeSetSchema для CodeSet'а — её version совпадает с
     * {@link CodeSetSnapshot#schemaVersion()}. Возвращает {@code Optional.empty()}, если
     * CodeSet удалён или схема почему-то отсутствует.
     */
    Optional<CodeSetSchemaSnapshot> currentSchema(UUID codesetId);

    /**
     * Конкретная ревизия схемы. Используется авторингом, чтобы валидировать CodeItem'ы
     * draft'а против схемы, которая была активной на момент создания этого draft'а — а не
     * против «текущей» схемы catalog'а (иначе изменение схемы посреди работы автора
     * сломает уже введённые items).
     */
    Optional<CodeSetSchemaSnapshot> schemaByVersion(UUID codesetId, int schemaVersion);

    /**
     * Краткий snapshot domain'а — нужен publishing'у (E9), чтобы попадать в
     * {@code domain_name} payload'а outbound webhook'а без cross-module imports.
     */
    Optional<DomainSnapshot> findDomain(UUID domainId);

    /**
     * Cross-codeset FK-связи справочника (E25, {@code catalog.code_set.column_refs}) —
     * нужны relational store'у (Stage 6), чтобы материализовать настоящие
     * {@code FOREIGN KEY} между {@code rd_data}-таблицами. Пустой список, если связей нет
     * или CodeSet удалён.
     */
    List<CodeSetReferenceSnapshot> referencesOf(UUID codesetId);

    /**
     * Snapshot CodeSet'а в form'е, нужной соседним модулям. Не путать с богатым
     * {@code bank.rdmmesh.spec.entity.CodeSet} POJO, у которого свои JSON-поля.
     *
     * @param hierarchyMode {@code "NONE" | "INTRA_CODESET" | "CROSS_CODESET"}
     * @param keySpecJson сырой JSON key_spec ({@code {"parts":[{"name","type",...}]}}) —
     *     нужен relational store'у для генерации ключевых колонок физической таблицы.
     */
    record CodeSetSnapshot(
            UUID id,
            UUID domainId,
            String name,
            String hierarchyMode,
            int schemaVersion,
            String currentPublishedVersion,
            String keySpecJson,
            boolean deleted) {}

    /** Активная (или конкретная) ревизия CodeSetSchema. */
    record CodeSetSchemaSnapshot(
            UUID codesetId,
            int version,
            /** Сырой JSON Schema-документ. Получатель валидирует attributes против него. */
            String jsonSchemaText) {}

    /** Краткий snapshot domain'а: id + qualified_name + display_name. */
    record DomainSnapshot(
            UUID id,
            String name,
            String displayName) {}

    /** Одна cross-codeset FK-связь (E25): колонка ЭТОГО справочника → колонка целевого. */
    record CodeSetReferenceSnapshot(
            String fromColumn,
            UUID toCodesetId,
            String toColumn) {}
}
