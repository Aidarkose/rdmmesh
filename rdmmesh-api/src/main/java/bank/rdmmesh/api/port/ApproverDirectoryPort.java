package bank.rdmmesh.api.port;

import java.util.List;
import java.util.UUID;

/**
 * Справочник ролей домена для адресной маршрутизации согласования
 * (SPEC §2.4 «Справочник ролей домена», BR-21/BR-22, ADR-009, handoff E17).
 *
 * <p>Отвечает на вопрос «кому Author может адресно отправить draft на
 * согласование в этом домене»: {@code домен → роль(STEWARD|BUSINESS_OWNER)
 * → учётная запись}. Источник истины — OpenMetadata; на текущем этапе
 * наполняется локальным сидом через {@link #reload(List)} (полная замена
 * TRUNCATE+INSERT), позже — справочником, сгенерированным в OM.
 *
 * <p>Это <b>не</b> {@link OwnershipPort} (тот — per-CodeSet asset-роли из
 * webhook'а OM, дельта-UPSERT). Реализация живёт в {@code rdmmesh-ownership}.
 */
public interface ApproverDirectoryPort {

    /** Допустимые directory-роли. */
    String STEWARD = "STEWARD";
    String BUSINESS_OWNER = "BUSINESS_OWNER";

    /**
     * Является ли {@code omUserId} назначаемым согласующим роли {@code role}
     * для домена {@code domainId} (есть строка в справочнике). Используется
     * workflow'ом при валидации {@code submit}-assignee.
     */
    boolean isAssignable(UUID domainId, String role, UUID omUserId);

    /**
     * Кандидаты-согласующие домена. {@code role == null} — все роли;
     * иначе фильтр по STEWARD/BUSINESS_OWNER. Для UI submit-диалога.
     */
    List<Approver> approversOf(UUID domainId, String role);

    /**
     * Полная замена справочника: {@code TRUNCATE} + {@code INSERT} всех
     * {@code entries} одной транзакцией (атомарная подмена снапшота, BR-22).
     * {@code omDomainId} каждого entry резолвится в {@code catalog.domain.id};
     * entries c неизвестным доменом пропускаются (возвращается фактически
     * вставленное число).
     *
     * @return число вставленных строк
     */
    int reload(List<DirectoryEntry> entries);

    /**
     * Адресное добавление одного согласующего для конкретного домена по
     * {@code domainId} (источник {@code RDM_ADMIN_LOCAL}). В отличие от
     * {@link #reload}, не резолвит {@code om_domain_id} и ничего не стирает —
     * подходит для локальных доменов без связи с OM. Идемпотентно (upsert по
     * {@code (domain_id, role, om_user_id)}).
     */
    void addLocal(UUID domainId, String role, UUID omUserId, String username, String displayName);

    /**
     * Удаляет одного согласующего домена. Возвращает {@code true}, если строка
     * существовала и была удалена.
     */
    boolean removeLocal(UUID domainId, String role, UUID omUserId);

    /** Кандидат-согласующий (для approvers-эндпоинта UI). */
    record Approver(UUID omUserId, String username, String displayName, String role) {}

    /** Входная строка снапшота справочника (из локального сида / OM). */
    record DirectoryEntry(
            UUID omDomainId,
            String role,
            UUID omUserId,
            String username,
            String displayName) {}
}
