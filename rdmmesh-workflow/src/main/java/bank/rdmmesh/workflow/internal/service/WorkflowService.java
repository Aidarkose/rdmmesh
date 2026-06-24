package bank.rdmmesh.workflow.internal.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.CatalogReadPort.CodeSetSnapshot;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.VersionLifecyclePort.TransitionEffect;
import bank.rdmmesh.api.port.VersionLifecyclePort.VersionSnapshot;
import bank.rdmmesh.api.port.WorkflowPort.IllegalStateTransitionException;
import bank.rdmmesh.spec.api.TransitionRequest;
import bank.rdmmesh.spec.entity.AssetOwnership;
import bank.rdmmesh.spec.events.WorkflowTransitionEvent;
import bank.rdmmesh.workflow.internal.StateMachine;
import bank.rdmmesh.workflow.internal.StateMachine.Action;
import bank.rdmmesh.workflow.internal.StateMachine.Decision;
import bank.rdmmesh.workflow.internal.StateMachine.Status;
import bank.rdmmesh.workflow.internal.WorkflowGraph;
import bank.rdmmesh.workflow.internal.WorkflowGraphCodec;
import bank.rdmmesh.workflow.internal.WorkflowGraphInvariants;
import bank.rdmmesh.workflow.internal.dao.ApprovalTaskDao;
import bank.rdmmesh.workflow.internal.dao.ApprovalTaskDao.ApprovalTaskRow;
import bank.rdmmesh.workflow.internal.dao.WorkflowTemplateDao;
import bank.rdmmesh.workflow.internal.dao.WorkflowTransitionDao;
import bank.rdmmesh.workflow.internal.dao.WorkflowTransitionDao.TransitionRow;

/**
 * Оркестратор 4-eyes workflow. Связывает чистую {@link StateMachine} с side-DB-операциями:
 * <ol>
 *   <li>загрузка контекста (current status, created_by, reviewers, assetRoles, domainId);</li>
 *   <li>{@link StateMachine#validate} — все проверки в pure-логике;</li>
 *   <li>в одной транзакции authoring'а: CAS статуса + reviewer/approver side-effect;</li>
 *   <li>append-only INSERT в {@code workflow.workflow_transition} + upsert/close
 *       {@code workflow.approval_task};</li>
 *   <li>publish {@link WorkflowTransitionEvent} в in-process bus (audit/publishing
 *       подписываются в E10/E6).</li>
 * </ol>
 *
 * <p><b>Атомарность (E14 round 5).</b> Status CAS (authoring schema) + INSERT
 * в transition log + UPSERT approval_task (workflow schema) теперь идут в
 * <b>одной</b> Postgres tx через {@link Jdbi#inTransaction}. Promote'нутый
 * {@link VersionLifecyclePort#transition(org.jdbi.v3.core.Handle, UUID, String,
 * String, UUID, VersionLifecyclePort.TransitionEffect)} overload принимает
 * shared Handle. Если любая из трёх операций упадёт — Postgres rollback'ает
 * все три, и журнал остаётся консистентным со статусом версии.
 *
 * <p><b>EventBus.publish — после commit'а.</b> Подписчики (audit, publishing
 * auto-publish) подписываются на in-process bus и пишут в свои схемы в
 * собственных tx. Если такая subscriber-tx упадёт — основная workflow-tx
 * уже зафиксирована и не откатывается. Это best-effort по design'у audit'а
 * (SPEC §3.8): append-failure не должен блокировать бизнес-операцию.
 */
public final class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final Jdbi jdbi;
    private final VersionLifecyclePort lifecycle;
    private final OwnershipPort ownership;
    private final CatalogReadPort catalog;
    private final EventBus eventBus;
    /** E17 / BR-21: справочник ролей домена. {@code null} → адресная
     *  маршрутизация submit'а отключена (legacy broadcast). */
    private final bank.rdmmesh.api.port.ApproverDirectoryPort approverDirectory;
    /** Stage 7: проверка ссылочной целостности перед submit'ом; {@code null} → гейт выключен. */
    private final bank.rdmmesh.api.port.ReferenceIntegrityPort referenceIntegrity;

    public WorkflowService(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            OwnershipPort ownership,
            CatalogReadPort catalog,
            EventBus eventBus) {
        this(jdbi, lifecycle, ownership, catalog, eventBus, null);
    }

    public WorkflowService(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            OwnershipPort ownership,
            CatalogReadPort catalog,
            EventBus eventBus,
            bank.rdmmesh.api.port.ApproverDirectoryPort approverDirectory) {
        this(jdbi, lifecycle, ownership, catalog, eventBus, approverDirectory, null);
    }

    public WorkflowService(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            OwnershipPort ownership,
            CatalogReadPort catalog,
            EventBus eventBus,
            bank.rdmmesh.api.port.ApproverDirectoryPort approverDirectory,
            bank.rdmmesh.api.port.ReferenceIntegrityPort referenceIntegrity) {
        this.jdbi = jdbi;
        this.lifecycle = lifecycle;
        this.ownership = ownership;
        this.catalog = catalog;
        this.eventBus = eventBus;
        this.approverDirectory = approverDirectory;
        this.referenceIntegrity = referenceIntegrity;
    }

    public WorkflowTransitionEvent transition(
            UUID versionId,
            String targetStatus,
            UUID actor,
            Set<String> baseRoles,
            String comment) {

        VersionSnapshot version = lifecycle.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));

        Status from = parseStatus(version.status());
        Status to   = parseStatus(targetStatus);

        CodeSetSnapshot codeSet = catalog.findCodeSet(version.codesetId())
                .orElseThrow(() -> new IllegalStateException(
                        "CodeSet missing for version " + versionId + " (codeset=" + version.codesetId() + ")"));

        Set<String> assetRoles = new java.util.HashSet<>(
                ownership.rolesOf(version.codesetId(), actor));
        Set<UUID> reviewers = lifecycle.reviewersOf(versionId);

        // E17 / BR-21: маршрут версии (кого Author выбрал согласующими при
        // submit'е). Адресат, назначенный через справочник ролей домена,
        // получает asset-роль СВОЕЙ ступени именно для этой версии — без
        // ослабления self-approval/no-bypass (StateMachine всё так же
        // проверяет actor≠createdBy, actor∉reviewers; граф-инварианты не
        // тронуты). assignee submit'а приходит из REST через ThreadLocal.
        java.util.Optional<bank.rdmmesh.workflow.internal.dao.VersionRouteDao.VersionRouteRow>
                route = jdbi.withExtension(
                        bank.rdmmesh.workflow.internal.dao.VersionRouteDao.class,
                        d -> d.findByVersion(versionId));
        route.ifPresent(r -> {
            if (actor.equals(r.stewardUserId())) {
                assetRoles.add("STEWARD");
            }
            if (actor.equals(r.ownerUserId())) {
                assetRoles.add("OWNER");
            }
        });

        SubmitAssigneeHolder.Assignee assignee =
                (to == Status.IN_REVIEW) ? SubmitAssigneeHolder.get() : null;
        if (assignee != null && approverDirectory != null) {
            validateAssignee(assignee, codeSet.domainId(), version.createdBy());
        }

        // Stage 7: жёсткий гейт ссылочной целостности — нельзя отправить на ревью
        // версию с «висячими» ссылками (значение FK-колонки отсутствует в
        // опубликованном родителе).
        if (to == Status.IN_REVIEW && referenceIntegrity != null) {
            java.util.List<String> viol = referenceIntegrity.violations(versionId);
            if (!viol.isEmpty()) {
                throw new IllegalStateTransitionException(
                        "нельзя отправить на ревью — нарушена ссылочная целостность: "
                                + String.join("; ", viol));
            }
        }

        StateMachine.Request req = new StateMachine.Request(
                from, to, actor, version.createdBy(),
                reviewers, assetRoles, baseRoles, comment);
        // ADR-0010 B2: легальность проверяется против ГРАФА ДОМЕНА версии
        // (per-domain BPMN), иначе дефолтный 4-eyes. resolveGraph fail-safe
        // к дефолту + re-validate инвариантов (defense-in-depth: даже
        // tampered DB-row не ослабит no-bypass).
        WorkflowGraph graph = resolveGraph(codeSet.domainId());
        Decision decision = StateMachine.validate(req, graph);

        TransitionEffect effect = new TransitionEffect(
                decision.recordReviewer(), decision.setApprover());

        // Кандидаты на следующую approval-task'у выводим заранее, ДО открытия
        // tx: ownership-lookup делает свою короткую read-tx, и держать её
        // внутри write-tx — увеличивает время удержания row-lock'а на
        // code_set_version. Plain pre-fetch вне tx — корректен (роли в OM
        // меняются через webhook, который тоже идёт через свою tx).
        String nextRole = graph.nextRequiredRole(to);
        // E17 / BR-21: адресная задача. submit → STEWARD-задача выбранному
        // steward'у; после steward_approve → OWNER-задача выбранному
        // business-owner'у (из version_route). Нет assignee/маршрута →
        // legacy broadcast по rdm_asset_ownership (обратная совместимость:
        // ITs, вызывающие service напрямую без assignee).
        UUID[] candidates;
        String assignedRole;
        if (nextRole == null) {
            candidates = null;
            assignedRole = null;
        } else if (assignee != null && "STEWARD".equals(nextRole)) {
            candidates = new UUID[] { assignee.stewardUserId() };
            assignedRole = bank.rdmmesh.api.port.ApproverDirectoryPort.STEWARD;
        } else if (route.isPresent() && "OWNER".equals(nextRole)) {
            candidates = new UUID[] { route.get().ownerUserId() };
            assignedRole = bank.rdmmesh.api.port.ApproverDirectoryPort.BUSINESS_OWNER;
        } else if (route.isPresent() && "STEWARD".equals(nextRole)) {
            candidates = new UUID[] { route.get().stewardUserId() };
            assignedRole = bank.rdmmesh.api.port.ApproverDirectoryPort.STEWARD;
        } else {
            candidates = candidatesFor(version.codesetId(), nextRole);
            assignedRole = null;
        }

        UUID transitionId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        // E14 round 5: ОДНА tx на CAS статуса (authoring) + journal INSERT
        // (workflow) + approval-task UPSERT (workflow). Раньше эти три шага
        // были в трёх независимых tx — между ними была щель для inconsistency
        // (status changed, журнала нет).
        boolean applied = jdbi.inTransaction(handle -> {
            boolean ok = lifecycle.transition(
                    handle, versionId, version.status(), targetStatus, actor, effect);
            if (!ok) {
                return false;
            }
            handle.attach(WorkflowTransitionDao.class).insert(
                    transitionId,
                    versionId,
                    version.codesetId(),
                    codeSet.domainId(),
                    version.status(),
                    targetStatus,
                    decision.action().name(),
                    actor,
                    comment);

            // E17: атомарно с CAS статуса сохраняем выбранный маршрут
            // (steward+business-owner), чтобы после steward_approve можно
            // было адресовать OWNER-задачу выбранному бизнес-владельцу.
            if (assignee != null) {
                handle.attach(
                        bank.rdmmesh.workflow.internal.dao.VersionRouteDao.class)
                        .upsert(versionId, codeSet.domainId(), version.codesetId(),
                                assignee.stewardUserId(), assignee.ownerUserId(),
                                version.createdBy());
            }

            ApprovalTaskDao tasks = handle.attach(ApprovalTaskDao.class);
            // Закрыть текущую задачу для актора (он же её только что выполнил).
            tasks.closeAll(versionId, actor);
            // Открыть следующую задачу для соответствующей роли — если есть.
            if (nextRole != null) {
                tasks.upsertOpen(versionId, version.codesetId(), codeSet.domainId(),
                        nextRole, assignedRole, candidates);
            }
            return true;
        });

        if (!applied) {
            // Concurrent transition: пока мы валидировали и зашли в tx, кто-то
            // перевёл версию в другой статус. CAS вернул 0, мы откатились —
            // status в БД не менялся.
            throw new IllegalStateTransitionException(
                    "Конкурентный transition: версия " + versionId
                            + " больше не в статусе " + version.status());
        }

        log.info("workflow: {} → {} version_id={} action={} actor={}",
                version.status(), targetStatus, versionId, decision.action(), actor);

        WorkflowTransitionEvent event = buildEvent(
                transitionId, versionId, version.codesetId(), codeSet.domainId(),
                version.status(), targetStatus, decision.action(),
                actor, comment, occurredAt);
        try {
            // Publish ПОСЛЕ commit'а: подписчики (audit, publishing.autoPublish)
            // должны видеть зафиксированное состояние, не in-flight tx. Если
            // тут упадёт — основная tx уже на диске; audit best-effort (SPEC §3.8).
            eventBus.publish(new WorkflowTransitionDomainEvent(
                    transitionId,
                    OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                    event));
        } catch (RuntimeException e) {
            log.warn("workflow: event publish failed (transition_id={}): {}",
                    transitionId, e.toString());
        }
        return event;
    }


    public List<WorkflowTransitionEvent> history(UUID versionId) {
        // Проверим что версия есть — иначе history впустую вернёт пустой список и проглотит 404.
        VersionSnapshot version = lifecycle.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));
        List<TransitionRow> rows = jdbi.withExtension(WorkflowTransitionDao.class,
                dao -> dao.findByVersion(versionId));
        return rows.stream().map(WorkflowService::toEvent).toList();
    }

    public List<ApprovalTaskRow> openTasksFor(UUID omUserId) {
        return jdbi.withExtension(ApprovalTaskDao.class,
                dao -> dao.findOpenByUser(omUserId));
    }

    public Optional<ApprovalTaskRow> openTaskFor(UUID versionId, String requiredRole) {
        return jdbi.withExtension(ApprovalTaskDao.class,
                dao -> dao.findOne(versionId, requiredRole))
                .filter(r -> r.closedAt() == null);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * E17 / BR-21: валидация выбранного при submit'е согласующего. Тройка
     * {@code (domain, role, user)} должна существовать в справочнике ролей
     * домена; домен обязан совпадать с доменом CodeSet'а; сохраняется
     * правило self-approval (адресат ≠ автор draft'а). Ошибки маппятся в
     * REST как 409 (IllegalStateTransition) / 409 (SelfApproval).
     */
    private void validateAssignee(
            SubmitAssigneeHolder.Assignee a, UUID codeSetDomainId, UUID createdBy) {
        if (a.stewardUserId() == null || a.ownerUserId() == null) {
            throw new IllegalStateTransitionException(
                    "submit требует assignee: steward и business-owner");
        }
        if (a.domainId() == null || !a.domainId().equals(codeSetDomainId)) {
            throw new IllegalStateTransitionException(
                    "assignee.domain_id не совпадает с доменом CodeSet'а");
        }
        if (!approverDirectory.isAssignable(
                codeSetDomainId,
                bank.rdmmesh.api.port.ApproverDirectoryPort.STEWARD,
                a.stewardUserId())) {
            throw new IllegalStateTransitionException(
                    "выбранный steward не значится в справочнике ролей домена");
        }
        if (!approverDirectory.isAssignable(
                codeSetDomainId,
                bank.rdmmesh.api.port.ApproverDirectoryPort.BUSINESS_OWNER,
                a.ownerUserId())) {
            throw new IllegalStateTransitionException(
                    "выбранный business-owner не значится в справочнике ролей домена");
        }
        if (a.stewardUserId().equals(createdBy) || a.ownerUserId().equals(createdBy)) {
            throw new bank.rdmmesh.api.port.WorkflowPort.SelfApprovalException(
                    "Self-approval запрещён: согласующий не может быть автором draft'а");
        }
    }


    /**
     * Граф топологии для домена версии (ADR-0010 B2). Активный шаблон с
     * {@code graph_json} → его граф; нет/NULL → дефолтный 4-eyes.
     *
     * <p><b>Fail-safe + defense-in-depth.</b> Любой сбой (битый/tampered
     * JSON, не прошёл {@link WorkflowGraphInvariants}) → дефолтный
     * 4-eyes (строжайший known-good — НЕ ослабление no-bypass). Инварианты
     * прогоняются и на чтении, не только при деплое: подмена строки в БД
     * не может ослабить 4-eyes в рантайме.
     */
    private WorkflowGraph resolveGraph(UUID domainId) {
        if (domainId == null) {
            return WorkflowGraph.defaultFourEyes();
        }
        try {
            String gj = jdbi.withExtension(WorkflowTemplateDao.class,
                            d -> d.findActiveByDomain(domainId))
                    .map(WorkflowTemplateDao.TemplateRow::graphJson)
                    .orElse(null);
            if (gj == null || gj.isBlank()) {
                return WorkflowGraph.defaultFourEyes();
            }
            WorkflowGraph g = WorkflowGraphCodec.fromJson(gj);
            WorkflowGraphInvariants.validate(g);
            log.debug("workflow: per-domain graph domain={} edges={}",
                    domainId, g.edges().size());
            return g;
        } catch (RuntimeException e) {
            log.warn("workflow: per-domain graph load failed domain={} → "
                    + "default 4-eyes (fail-safe): {}", domainId, e.toString());
            return WorkflowGraph.defaultFourEyes();
        }
    }

    private UUID[] candidatesFor(UUID codesetId, String requiredRole) {
        List<AssetOwnership> all = ownership.ownersOf(codesetId, "CODESET");
        return all.stream()
                .filter(a -> a.getRole() != null && requiredRole.equals(a.getRole().value()))
                .filter(a -> a.getOmUserId() != null)
                .map(a -> UUID.fromString(a.getOmUserId()))
                .distinct()
                .toArray(UUID[]::new);
    }

    private static WorkflowTransitionEvent buildEvent(
            UUID id,
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String fromStatus,
            String toStatus,
            Action action,
            UUID actor,
            String comment,
            Instant occurredAt) {
        // Setters сгенерированного POJO принимают String — javaType из JSON Schema
        // jsonschema2pojo тут игнорирует (см. AuthoringMappers / CatalogMappers,
        // тот же паттерн).
        WorkflowTransitionEvent e = new WorkflowTransitionEvent();
        e.setEventId(id.toString());
        e.setVersionId(versionId.toString());
        e.setCodesetId(codesetId == null ? null : codesetId.toString());
        e.setDomainId(domainId == null ? null : domainId.toString());
        e.setFrom(TransitionRequest.VersionStatus.fromValue(fromStatus));
        e.setTo(TransitionRequest.VersionStatus.fromValue(toStatus));
        e.setAction(WorkflowTransitionEvent.TransitionAction.fromValue(action.name()));
        e.setActor(actor.toString());
        e.setComment(comment);
        e.setOccurredAt(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC).toString());
        return e;
    }

    private static WorkflowTransitionEvent toEvent(TransitionRow row) {
        WorkflowTransitionEvent e = new WorkflowTransitionEvent();
        e.setEventId(row.id().toString());
        e.setVersionId(row.versionId().toString());
        e.setCodesetId(row.codesetId() == null ? null : row.codesetId().toString());
        e.setDomainId(row.domainId() == null ? null : row.domainId().toString());
        e.setFrom(row.fromStatus() == null ? null
                : TransitionRequest.VersionStatus.fromValue(row.fromStatus()));
        e.setTo(TransitionRequest.VersionStatus.fromValue(row.toStatus()));
        e.setAction(WorkflowTransitionEvent.TransitionAction.fromValue(row.action()));
        e.setActor(row.actor().toString());
        e.setComment(row.comment());
        e.setOccurredAt(OffsetDateTime.ofInstant(row.occurredAt(), ZoneOffset.UTC).toString());
        return e;
    }

    private static Status parseStatus(String s) {
        if (s == null) {
            throw new IllegalStateTransitionException("Статус не задан");
        }
        try {
            return Status.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateTransitionException("Неизвестный статус: " + s);
        }
    }
}
