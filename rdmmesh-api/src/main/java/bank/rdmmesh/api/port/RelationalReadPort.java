package bank.rdmmesh.api.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only порт реляционного стора ({@code rd_data}) для соседних модулей (Stage 7b).
 * Через него {@code rdmmesh-distribution} читает items опубликованной версии из физических
 * таблиц справочника без cross-module-импортов и без обращения к jsonb {@code code_item}.
 *
 * <p>Реализация живёт в {@code rdmmesh-authoring} (проксирует {@code RelationalStoreService}).
 * Резолв версии/кодсета (semver, {@code knowledge_as_of}) остаётся за вызывающим — порт лишь
 * отдаёт строки конкретной версии из {@code "<base>__history"}.
 */
public interface RelationalReadPort {

    /**
     * Items опубликованной версии (снимок из {@code rd_data."<base>__history"}) в нейтральном
     * row-DTO. Пусто, если справочник не материализован или версия не публиковалась в rd_data.
     */
    List<RelationalItem> publishedItems(UUID codesetId, UUID versionId);

    /**
     * Нейтральная строка справочника (без HTTP/jsonb-специфики). {@code keyParts}/{@code parentKey}
     * — списки строк; {@code attributes} — map; даты — строки ISO либо null.
     */
    record RelationalItem(
            List<String> keyParts,
            List<String> parentKey,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Map<String, Object> attributes,
            Integer orderIndex,
            String status,
            String effectiveFrom,
            String effectiveTo) {}
}
