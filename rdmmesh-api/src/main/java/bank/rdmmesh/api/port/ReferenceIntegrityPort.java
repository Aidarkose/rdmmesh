package bank.rdmmesh.api.port;

import java.util.List;
import java.util.UUID;

/**
 * Жёсткая проверка ссылочной целостности версии (Stage 7): значения колонок со
 * связями ({@code column_refs}) должны существовать в опубликованных родительских
 * справочниках. Используется workflow'ом перед submit'ом (DRAFT → IN_REVIEW),
 * чтобы версия с «висячими» ссылками не уходила на ревью.
 *
 * <p>Реализация — в модуле {@code rdmmesh-authoring} (он владеет rd_data).
 */
public interface ReferenceIntegrityPort {

    /**
     * Список человекочитаемых нарушений по черновику версии (пусто — всё ок).
     * Каждая строка вида {@code from_column=<val> не найден в <parent>.<to_column>}.
     */
    List<String> violations(UUID versionId);
}
