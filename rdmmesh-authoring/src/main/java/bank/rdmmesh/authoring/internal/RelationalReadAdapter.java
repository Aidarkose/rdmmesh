package bank.rdmmesh.authoring.internal;

import java.util.List;
import java.util.UUID;

import bank.rdmmesh.api.port.RelationalReadPort;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService;
import bank.rdmmesh.authoring.resource.CodeItemDto;

/**
 * Реализация {@link RelationalReadPort} поверх {@link RelationalStoreService}: отдаёт items
 * опубликованной версии из {@code rd_data."<base>__history"} соседним модулям (Stage 7b,
 * distribution). Маппит {@link CodeItemDto} в нейтральный {@link RelationalItem}.
 */
public final class RelationalReadAdapter implements RelationalReadPort {

    private final RelationalStoreService store;

    public RelationalReadAdapter(RelationalStoreService store) {
        this.store = store;
    }

    @Override
    public List<RelationalItem> publishedItems(UUID codesetId, UUID versionId) {
        try {
            return store.listPublishedItems(versionId).stream()
                    .map(RelationalReadAdapter::toItem)
                    .toList();
        } catch (IllegalStateException notProvisioned) {
            // справочник ещё не материализован в rd_data — пусто (graceful, как E25).
            return List.of();
        }
    }

    private static RelationalItem toItem(CodeItemDto d) {
        return new RelationalItem(
                d.keyParts(),
                d.parentKey(),
                d.labelRu(),
                d.labelEn(),
                d.descriptionRu(),
                d.descriptionEn(),
                d.attributes(),
                d.orderIndex(),
                d.status(),
                d.effectiveFrom(),
                d.effectiveTo());
    }
}
