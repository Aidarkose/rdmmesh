package bank.rdmmesh.workflow.internal.dao;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO реестра per-domain BPMN-шаблонов (миграция V032, V2 / BR-18 round 2).
 *
 * <p>Append-only-по-смыслу: строки не удаляются; смена шаблона домена —
 * новая строка (version++), прежняя {@code active=false} (см.
 * {@link #deactivateActive}). Активный шаблон домена — максимум один
 * (partial unique index).
 */
public interface WorkflowTemplateDao {

    String COLUMNS =
            "id, domain_id, process_key, flowable_deployment_id, bpmn_sha256,"
                    + " version, deployed_by, deployed_at, active, graph_json";

    @SqlUpdate(
            "INSERT INTO workflow.workflow_template"
                    + " (domain_id, process_key, flowable_deployment_id, bpmn_sha256,"
                    + "  version, deployed_by, graph_json)"
                    + " VALUES (:domainId, :processKey, :flowableDeploymentId,"
                    + "         :bpmnSha256, :version, :deployedBy, :graphJson)")
    int insert(
            @Bind("domainId") UUID domainId,
            @Bind("processKey") String processKey,
            @Bind("flowableDeploymentId") String flowableDeploymentId,
            @Bind("bpmnSha256") String bpmnSha256,
            @Bind("version") int version,
            @Bind("deployedBy") UUID deployedBy,
            @Bind("graphJson") String graphJson);

    /** Снимает active с текущего активного шаблона домена (перед вставкой нового). */
    @SqlUpdate("UPDATE workflow.workflow_template SET active = false"
            + " WHERE domain_id = :domainId AND active")
    int deactivateActive(@Bind("domainId") UUID domainId);

    @SqlQuery("SELECT coalesce(max(version), 0) + 1"
            + " FROM workflow.workflow_template WHERE domain_id = :domainId")
    int nextVersion(@Bind("domainId") UUID domainId);

    @SqlQuery("SELECT " + COLUMNS + " FROM workflow.workflow_template"
            + " WHERE domain_id = :domainId AND active")
    @RegisterConstructorMapper(TemplateRow.class)
    Optional<TemplateRow> findActiveByDomain(@Bind("domainId") UUID domainId);

    @SqlQuery("SELECT " + COLUMNS + " FROM workflow.workflow_template"
            + " ORDER BY domain_id, version DESC")
    @RegisterConstructorMapper(TemplateRow.class)
    List<TemplateRow> listAll();

    record TemplateRow(
            UUID id,
            UUID domainId,
            String processKey,
            String flowableDeploymentId,
            String bpmnSha256,
            int version,
            UUID deployedBy,
            Instant deployedAt,
            boolean active,
            String graphJson) {}
}
