package bank.rdmmesh.ownership.internal.om;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.ApproverDirectoryPort;
import bank.rdmmesh.api.port.ApproverDirectoryPort.DirectoryEntry;
import bank.rdmmesh.api.port.CatalogMirrorPort;
import bank.rdmmesh.api.port.CatalogMirrorPort.DomainMirror;
import bank.rdmmesh.api.port.CatalogMirrorPort.RoleMirror;
import bank.rdmmesh.ownership.internal.om.OpenMetadataCatalogClient.OmEntity;
import bank.rdmmesh.ownership.internal.om.OpenMetadataCatalogClient.OmUserRef;

/**
 * Pull-синхронизация доменов, ролей и справочника ролей домена из OpenMetadata в
 * RDM-зеркало. Вызывается из {@code CatalogSyncWebhookResource} по тонкому уведомлению
 * OM Alert {@code Domain_and_Roles_sync_for_RDMmesh}. Стратегия — полный идемпотентный
 * ресинк (на любое уведомление тянем весь список доменов и ролей и апсертим).
 *
 * <p>Phase 4: из OM-доменов извлекаются {@code owners}→BUSINESS_OWNER и
 * {@code experts}→STEWARD и полностью заменяют {@code domain_role_directory}
 * РЕАЛЬНЫМИ OM-user-id (через {@link ApproverDirectoryPort#reload}) — конец дрейфа
 * LOCAL_SEED. om_user_id в справочнике совпадает с тем, что биндится в
 * rdm_user_mapping при логине (оба из OM).
 *
 * <p>Безопасность по ТЗ: данные тянутся через стандартный secure REST API OM (Bearer
 * bot-token), уведомление лишь триггерит pull.
 */
public final class CatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);

    private final OpenMetadataCatalogClient om;
    private final CatalogMirrorPort catalogMirror;
    private final ApproverDirectoryPort directory;

    public CatalogSyncService(
            OpenMetadataCatalogClient om,
            CatalogMirrorPort catalogMirror,
            ApproverDirectoryPort directory) {
        this.om = om;
        this.catalogMirror = catalogMirror;
        this.directory = directory;
    }

    /** Итог ресинка для тела HTTP-ответа и логов. */
    public record SyncResult(int domainsSynced, int domainsSkipped,
                             int rolesSynced, int rolesSkipped,
                             int directoryEntries) {}

    public SyncResult resync() {
        // Тянем домены один раз — и в зеркало, и в справочник ролей домена.
        List<OmEntity> domains = om.listDomains();
        int dOk = 0;
        int dSkip = 0;
        List<DirectoryEntry> dirEntries = new ArrayList<>();
        for (var d : domains) {
            UUID omId = tryUuid(d.id());
            if (omId == null) {
                dSkip++;
                continue;
            }
            String name = normalizeName(d.name());
            if (name == null) {
                log.warn("OM domain {} ({}) — имя не нормализуется в RDM-формат, пропуск",
                        d.id(), d.name());
                dSkip++;
                continue;
            }
            String displayName = d.displayName() != null ? d.displayName() : d.name();
            // parentId из OM (null у корневых доменов / при невалидном UUID) → корневой домен.
            UUID parentOmId = tryUuid(d.parentId());
            var res = catalogMirror.upsertDomainFromOm(new DomainMirror(
                    omId, parentOmId, name, displayName, d.description(), null, null, new String[0]));
            log.info("sync domain op={} om_id={} parent={} name={}", res.op(), omId, parentOmId, name);
            dOk++;

            // owners→BUSINESS_OWNER, experts→STEWARD (только OM-пользователи, не команды).
            collect(dirEntries, omId, d.owners(), ApproverDirectoryPort.BUSINESS_OWNER);
            collect(dirEntries, omId, d.experts(), ApproverDirectoryPort.STEWARD);
        }

        // Полная замена справочника ролей домена из OM — только если домены реально
        // получены (пустой список = ошибка pull'а; не вайпаем существующий справочник).
        int dirN = 0;
        if (!domains.isEmpty()) {
            dirN = directory.reload(dirEntries, "OM_GENERATED");
        } else {
            log.warn("catalog-sync: список доменов OM пуст — reload справочника ролей пропущен");
        }

        int rOk = 0;
        int rSkip = 0;
        for (var r : om.listRoles()) {
            UUID omId = tryUuid(r.id());
            if (omId == null) {
                rSkip++;
                continue;
            }
            String displayName = r.displayName() != null ? r.displayName() : r.name();
            var res = catalogMirror.upsertRoleFromOm(new RoleMirror(
                    omId, r.name(), displayName, r.description()));
            log.info("sync role op={} om_id={} name={}", res.op(), omId, r.name());
            rOk++;
        }

        var result = new SyncResult(dOk, dSkip, rOk, rSkip, dirN);
        log.info("catalog-sync завершён: {}", result);
        return result;
    }

    /** Конвертирует OM owners/experts домена в DirectoryEntry'и заданной роли. */
    private static void collect(
            List<DirectoryEntry> out, UUID omDomainId, List<OmUserRef> refs, String role) {
        if (refs == null) {
            return;
        }
        for (OmUserRef u : refs) {
            // В справочник попадают только OM-пользователи (type=user), не команды.
            if (u.type() != null && !"user".equalsIgnoreCase(u.type())) {
                continue;
            }
            UUID omUserId = tryUuid(u.id());
            if (omUserId == null) {
                continue;
            }
            out.add(new DirectoryEntry(
                    omDomainId, role, omUserId,
                    u.name(), u.displayName() != null ? u.displayName() : u.name()));
        }
    }

    private static UUID tryUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Приводит имя OM-домена к RDM-регексу {@code ^[a-z][a-z0-9_]{0,63}$}:
     * lower-case, не-[a-z0-9]→'_', обрезка ведущих не-букв, лимит 64. Возвращает null,
     * если после нормализации не остаётся валидного идентификатора.
     */
    static String normalizeName(String raw) {
        if (raw == null) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '_');
        }
        // обрезаем ведущие символы, пока первый не станет буквой
        int start = 0;
        while (start < sb.length() && !(sb.charAt(start) >= 'a' && sb.charAt(start) <= 'z')) {
            start++;
        }
        if (start >= sb.length()) return null;
        String s = sb.substring(start);
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }
}
