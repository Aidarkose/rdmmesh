package bank.rdmmesh.distribution.internal.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.distribution.internal.KeyEncoding;
import bank.rdmmesh.distribution.internal.VersionResolver;
import bank.rdmmesh.distribution.internal.dao.DistributionDao;
import bank.rdmmesh.distribution.internal.dao.DistributionDao.CodeSetRef;
import bank.rdmmesh.distribution.internal.dao.DistributionDao.ItemRow;
import bank.rdmmesh.distribution.internal.dao.DistributionDao.VersionRef;

/**
 * Бизнес-логика consumer-API. На вход — простые value-object'ы запроса, на выход —
 * либо {@link ItemsPage}, либо {@link Optional} для lookup'а. Никаких HTTP-деталей.
 *
 * <p>Алгоритм resolve версии (SPEC §2.3 + §3.5):
 * <ol>
 *   <li>Если задан {@code knowledge_as_of} — берём версию, известную системе на эту дату
 *       (bitemporal system time, фильтр по {@code tstzrange(system_from, system_to)}).</li>
 *   <li>Иначе если {@code version=published} (default) — последняя PUBLISHED по
 *       {@code published_at}.</li>
 *   <li>Иначе ({@code version=<semver>}) — точный matched версия в статусе
 *       PUBLISHED либо DEPRECATED.</li>
 * </ol>
 *
 * <p>Items фильтруются по {@code as_of} (effective time), если указан. Если оба параметра
 * отсутствуют — возвращается «текущий» published-snapshot.
 */
public final class DistributionService {

    private final Jdbi jdbi;
    private final ObjectMapper json;

    public DistributionService(Jdbi jdbi, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
    }

    public ItemsPage fetchItems(Query q) {
        return jdbi.withExtension(DistributionDao.class, dao -> {
            CodeSetRef ref = resolveCodeSet(dao, q.domain(), q.codeset());
            VersionRef ver = resolveVersion(dao, ref, q);
            int total;
            List<ItemRow> rows;
            int offset = (q.page() - 1) * q.size();
            if (q.asOf() != null) {
                total = dao.countItemsEffectiveAt(ver.id(), q.asOf());
                rows = dao.findItemsPageEffectiveAt(ver.id(), q.asOf(), offset, q.size());
            } else {
                total = dao.countItems(ver.id());
                rows = dao.findItemsPage(ver.id(), offset, q.size());
            }
            return toItemsPage(ref, ver, q, rows, total);
        });
    }

    public Optional<ItemDto> lookup(Query q, String keyToken) {
        var keyParts = KeyEncoding.decode(keyToken);
        String keyJson = KeyEncoding.toJsonArray(keyParts);
        return jdbi.withExtension(DistributionDao.class, dao -> {
            CodeSetRef ref = resolveCodeSet(dao, q.domain(), q.codeset());
            VersionRef ver = resolveVersion(dao, ref, q);
            return dao.lookup(ver.id(), keyJson)
                    .map(row -> toItem(row, q.lang()));
        });
    }

    /**
     * Стрим всех items версии — для bulk-export. Без пагинации; вызывающий собирает
     * всё в память (на пилотных объёмах десятки тысяч строк это безопасно). При росте —
     * заменим на cursor-стрим, контракт port'а останется тем же.
     */
    public ExportResult fetchAllItems(Query q) {
        return jdbi.withExtension(DistributionDao.class, dao -> {
            CodeSetRef ref = resolveCodeSet(dao, q.domain(), q.codeset());
            VersionRef ver = resolveVersion(dao, ref, q);
            int total;
            List<ItemRow> rows;
            if (q.asOf() != null) {
                total = dao.countItemsEffectiveAt(ver.id(), q.asOf());
                rows = dao.findItemsPageEffectiveAt(ver.id(), q.asOf(), 0, total);
            } else {
                total = dao.countItems(ver.id());
                rows = dao.findItemsPage(ver.id(), 0, total);
            }
            List<ItemDto> items = new ArrayList<>(rows.size());
            for (ItemRow r : rows) items.add(toItem(r, q.lang()));
            return new ExportResult(ref.domainName(), ref.codesetName(),
                    ver.version(), ver.contentHash(), items);
        });
    }

    // ── resolvers ───────────────────────────────────────────────────────────────

    private CodeSetRef resolveCodeSet(DistributionDao dao, String domain, String codeset) {
        CodeSetRef ref = dao.findCodeSetRef(domain, codeset)
                .orElseThrow(() -> new NotFoundException(
                        "CodeSet '" + domain + "." + codeset + "' не найден"));
        if (ref.isDeleted()) {
            throw new NotFoundException(
                    "CodeSet '" + domain + "." + codeset + "' soft-deleted");
        }
        return ref;
    }

    private VersionRef resolveVersion(DistributionDao dao, CodeSetRef ref, Query q) {
        if (q.knowledgeAsOf() != null) {
            return dao.findVersionKnownAt(ref.codesetId(), q.knowledgeAsOf())
                    .orElseThrow(() -> new NotFoundException(
                            "На " + q.knowledgeAsOf() + " система не знала ни одной published-версии "
                                    + ref.domainName() + "." + ref.codesetName()));
        }
        if (q.version() instanceof VersionResolver.Semver s) {
            return dao.findVersionBySemver(ref.codesetId(), s.value())
                    .orElseThrow(() -> new NotFoundException(
                            "Версия " + s.value() + " не найдена для "
                                    + ref.domainName() + "." + ref.codesetName()));
        }
        // LatestPublished по умолчанию.
        return dao.findLatestPublished(ref.codesetId())
                .orElseThrow(() -> new NotFoundException(
                        "У " + ref.domainName() + "." + ref.codesetName()
                                + " нет ни одной published-версии"));
    }

    // ── projection ──────────────────────────────────────────────────────────────

    private ItemsPage toItemsPage(
            CodeSetRef ref, VersionRef ver, Query q, List<ItemRow> rows, int total) {
        List<ItemDto> items = new ArrayList<>(rows.size());
        for (ItemRow r : rows) items.add(toItem(r, q.lang()));
        return new ItemsPage(
                ref.domainName(),
                ref.codesetName(),
                ver.version(),
                ver.id().toString(),
                ver.status(),
                ver.contentHash(),
                ver.publishedAt() == null ? null : ver.publishedAt().toString(),
                q.page(),
                q.size(),
                total,
                items);
    }

    private ItemDto toItem(ItemRow r, String lang) {
        String label = pick(r.labelRu(), r.labelEn(), lang);
        String description = pick(r.descriptionRu(), r.descriptionEn(), lang);
        return new ItemDto(
                parseKeyParts(r.keyPartsJson()),
                parseKeyParts(r.parentKeyJson()),
                label,
                description,
                parseAttributes(r.attributesJson()),
                r.orderIndex(),
                r.status(),
                r.effectiveFrom() == null ? null : r.effectiveFrom().toString(),
                r.effectiveTo()   == null ? null : r.effectiveTo().toString());
    }

    private static String pick(String ru, String en, String lang) {
        if ("en".equalsIgnoreCase(lang)) {
            return en != null ? en : ru;
        }
        return ru != null ? ru : en;
    }

    private List<String> parseKeyParts(String jsonText) {
        if (jsonText == null) return null;
        try {
            return json.readValue(jsonText, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("малформированный key_parts JSON: " + jsonText, e);
        }
    }

    private java.util.Map<String, Object> parseAttributes(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return java.util.Map.of();
        try {
            return json.readValue(jsonText,
                    new TypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("малформированный attributes JSON: " + jsonText, e);
        }
    }

    // ── DTO для resource'ов ─────────────────────────────────────────────────────

    public record Query(
            String domain,
            String codeset,
            VersionResolver.VersionSpec version,
            LocalDate asOf,
            Instant knowledgeAsOf,
            String lang,
            int page,
            int size) {}

    public record ItemsPage(
            String domain,
            String codeset,
            String version,
            String versionId,
            String status,
            String contentHash,
            String publishedAt,
            int page,
            int size,
            int total,
            List<ItemDto> items) {}

    public record ItemDto(
            List<String> keyParts,
            List<String> parentKey,
            String label,
            String description,
            java.util.Map<String, Object> attributes,
            int orderIndex,
            String status,
            String effectiveFrom,
            String effectiveTo) {}

    public record ExportResult(
            String domain,
            String codeset,
            String version,
            String contentHash,
            List<ItemDto> items) {}

    /** Доменное «ничего не нашли» — resource превращает в 404. */
    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}
