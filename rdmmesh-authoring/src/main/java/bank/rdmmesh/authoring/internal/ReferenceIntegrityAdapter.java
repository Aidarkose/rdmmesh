package bank.rdmmesh.authoring.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import bank.rdmmesh.api.port.ReferenceIntegrityPort;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService;

/**
 * Реализация {@link ReferenceIntegrityPort} поверх relational store (Stage 7).
 * На submit проверяет обе стороны ссылочной целостности:
 * <ul>
 *   <li>сторона ребёнка — {@link RelationalStoreService#versionReferenceViolations(UUID)}:
 *       значение в ссылающейся колонке существует в опубликованном родителе;</li>
 *   <li>сторона родителя — {@link RelationalStoreService#versionRemovedReferencedViolations(UUID)}:
 *       черновик не убирает ключ, на который ещё ссылается ребёнок.</li>
 * </ul>
 */
public final class ReferenceIntegrityAdapter implements ReferenceIntegrityPort {

    private final RelationalStoreService relational;

    public ReferenceIntegrityAdapter(RelationalStoreService relational) {
        this.relational = relational;
    }

    @Override
    public List<String> violations(UUID versionId) {
        List<String> out = new ArrayList<>(relational.versionReferenceViolations(versionId));
        out.addAll(relational.versionRemovedReferencedViolations(versionId));
        return out;
    }
}
