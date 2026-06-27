package bank.rdmmesh.ownership.internal.om;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.CatalogMirrorPort;
import bank.rdmmesh.api.port.CatalogMirrorPort.DomainMirror;
import bank.rdmmesh.api.port.CatalogMirrorPort.RoleMirror;

/**
 * Pull-синхронизация доменов и ролей из OpenMetadata в RDM-зеркало. Вызывается из
 * {@code CatalogSyncWebhookResource} по тонкому уведомлению OM Alert
 * {@code Domain_and_Roles_sync_for_RDMmesh}. Стратегия — полный идемпотентный ресинк
 * (на любое уведомление тянем весь список доменов и ролей и апсертим).
 *
 * <p>Безопасность по ТЗ: данные тянутся через стандартный secure REST API OM (Bearer
 * bot-token), уведомление лишь триггерит pull.
 */
public final class CatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);

    private final OpenMetadataCatalogClient om;
    private final CatalogMirrorPort catalogMirror;

    public CatalogSyncService(OpenMetadataCatalogClient om, CatalogMirrorPort catalogMirror) {
        this.om = om;
        this.catalogMirror = catalogMirror;
    }

    /** Итог ресинка для тела HTTP-ответа и логов. */
    public record SyncResult(int domainsSynced, int domainsSkipped,
                             int rolesSynced, int rolesSkipped) {}

    public SyncResult resync() {
        int dOk = 0;
        int dSkip = 0;
        for (var d : om.listDomains()) {
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
            var res = catalogMirror.upsertDomainFromOm(new DomainMirror(
                    omId, name, displayName, d.description(), null, null, new String[0]));
            log.info("sync domain op={} om_id={} name={}", res.op(), omId, name);
            dOk++;
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

        var result = new SyncResult(dOk, dSkip, rOk, rSkip);
        log.info("catalog-sync завершён: {}", result);
        return result;
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
