import { useState } from "react";
import {
  Breadcrumb,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Space,
  Tag,
  Tooltip,
  Typography,
  App as AntApp,
} from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { api, apiMutations, adminMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { useAuth } from "@/auth/AuthContext";
import { ApiError } from "@/api/client";
import { Loader } from "@/components/Loader";
import { StatusTag } from "@/components/StatusTag";
import { RequestDeletionButton } from "@/components/RequestDeletionButton";
import { SchemaFieldOrderEditor } from "@/components/SchemaFieldOrderEditor";
import { SchemaAttributeEditor } from "@/components/SchemaAttributeEditor";
import { CodeSetReferencesPanel } from "@/components/CodeSetReferencesPanel";
import type { CodeSetVersion, VersionStatus } from "@/api/types";

// non-terminal статусы блокируют создание новой DRAFT'а — это at-most-one-open инвариант
// E5 §1.2 (см. handoff E5). PUBLISHED/DEPRECATED/REJECTED — terminal'ы, не блокируют.
const OPEN_STATUSES: ReadonlySet<VersionStatus> = new Set([
  "DRAFT",
  "IN_REVIEW",
  "STEWARD_APPROVED",
  "OWNER_APPROVED",
]);

export function CodeSetPage() {
  const { t } = useTranslation();
  const { codesetId } = useParams<{ codesetId: string }>();
  const id = codesetId!;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();

  const { baseRoles } = useAuth();
  const isAdmin = baseRoles.includes("RDM_ADMIN");
  // E24 — порядок полей схемы правят Schema Designer и Admin (бэкенд: @RolesAllowed).
  const canEditSchema = isAdmin || baseRoles.includes("RDM_SCHEMA_DESIGNER");
  // FK-связи между справочниками правят Author / Schema Designer / Admin (бэкенд: @RolesAllowed).
  const canEditRefs = canEditSchema || baseRoles.includes("RDM_AUTHOR");

  const codeset = useApi(() => api.getCodeSet(id), qk.codesets.one(id));
  const schema = useApi(() => api.getActiveSchema(id), qk.codesets.schema(id));
  const versions = useApi(() => api.listVersionsByCodeSet(id), qk.versions.byCodeset(id));

  const openVersion = versions.data?.find((v) => OPEN_STATUSES.has(v.status));

  const [renameOpen, setRenameOpen] = useState(false);
  const [renameForm] = Form.useForm();

  const rename = useMutation({
    mutationFn: (newName: string) => adminMutations.renameCodeset(id, newName, true),
    onSuccess: () => {
      message.success("Codeset переименован");
      setRenameOpen(false);
      renameForm.resetFields();
      queryClient.invalidateQueries({ queryKey: qk.codesets.one(id) });
      queryClient.invalidateQueries({ queryKey: qk.codesets.byDomain(codeset.data?.domain_id ?? "") });
    },
    onError: (e: Error) => message.error(e.message),
  });

  const remove = useMutation({
    mutationFn: () => adminMutations.deleteCodeset(id, false),
    onSuccess: () => {
      message.success("Codeset удалён (soft-delete)");
      queryClient.invalidateQueries({ queryKey: qk.codesets.byDomain(codeset.data?.domain_id ?? "") });
      navigate(`/domains/${codeset.data?.domain_id}`);
    },
    onError: (e: Error) => message.error(e.message),
  });

  const createDraft = useMutation({
    mutationFn: () => apiMutations.createDraft(id, {}),
    onSuccess: (created: CodeSetVersion) => {
      message.success(t("codeset.draftCreated", { version: created.version }));
      queryClient.invalidateQueries({ queryKey: qk.versions.byCodeset(id) });
      queryClient.invalidateQueries({ queryKey: qk.codesets.one(id) });
      navigate(`/versions/${created.id}`);
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  return (
    <>
      <Breadcrumb
        style={{ marginBottom: 16 }}
        items={[
          { title: <Link to="/catalog">{t("nav.catalog")}</Link> },
          {
            title: codeset.data ? (
              <Link to={`/domains/${codeset.data.domain_id}`}>{t("catalog.domain")}</Link>
            ) : (
              "..."
            ),
          },
          { title: codeset.data?.display_name ?? codeset.data?.name ?? "..." },
        ]}
      />

      <Card
        title={t("catalog.codeset")}
        style={{ marginBottom: 16 }}
        extra={
          isAdmin ? (
            <Space>
              <Button size="small" onClick={() => setRenameOpen(true)}>
                Rename
              </Button>
              <Popconfirm
                title="Удалить codeset?"
                description="Soft-delete; published-версии при их наличии заблокируют операцию."
                onConfirm={() => remove.mutate()}
              >
                <Button size="small" danger>
                  Delete
                </Button>
              </Popconfirm>
            </Space>
          ) : (
            // E22 — non-admin (Author) кидает заявку Admin'у на soft-delete.
            <RequestDeletionButton codesetId={id} />
          )
        }
      >
        <Loader {...codeset}>
          {(c) => (
            <Descriptions column={1} size="small">
              <Descriptions.Item label={t("catalog.displayName")}>
                {c.display_name ?? "—"}
              </Descriptions.Item>
              <Descriptions.Item label={t("catalog.name")}>{c.name}</Descriptions.Item>
              <Descriptions.Item label={t("catalog.description")}>
                {c.description ?? "—"}
              </Descriptions.Item>
              <Descriptions.Item label={t("catalog.hierarchy")}>
                <Tag>{c.hierarchy_mode}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t("catalog.publishedVersion")}>
                {c.current_published_version ? (
                  <Tag color="green">v{c.current_published_version}</Tag>
                ) : (
                  <Tag>{t("catalog.noPublished")}</Tag>
                )}
              </Descriptions.Item>
              <Descriptions.Item label={t("catalog.schemaVersion")}>
                {c.schema_version ?? "—"}
              </Descriptions.Item>
              <Descriptions.Item label="key_spec">
                <Typography.Text code copyable>
                  {JSON.stringify(c.key_spec)}
                </Typography.Text>
              </Descriptions.Item>
            </Descriptions>
          )}
        </Loader>
      </Card>

      <Card title="JSON Schema (active)" size="small" style={{ marginBottom: 16 }}>
        <Loader {...schema}>
          {(s) => (
            <Space direction="vertical" style={{ width: "100%" }} size={16}>
              <SchemaAttributeEditor
                codesetId={id}
                jsonSchema={s.json_schema}
                canEdit={canEditSchema}
              />
              <SchemaFieldOrderEditor
                codesetId={id}
                jsonSchema={s.json_schema}
                canEdit={canEditSchema}
              />
              <pre
                style={{
                  margin: 0,
                  background: "#fafafa",
                  padding: 12,
                  borderRadius: 4,
                  maxHeight: 320,
                  overflow: "auto",
                  fontSize: 12,
                }}
              >
                {JSON.stringify(s.json_schema, null, 2)}
              </pre>
            </Space>
          )}
        </Loader>
      </Card>

      {codeset.data && (
        <CodeSetReferencesPanel
          codeset={codeset.data}
          jsonSchema={schema.data?.json_schema}
          canEdit={canEditRefs}
        />
      )}

      <Card
        title={t("version.title")}
        extra={
          <Tooltip
            title={openVersion ? t("codeset.draftBlockedHint", { version: openVersion.version }) : undefined}
          >
            {/* span обёртка: AntD Button с disabled не пропускает hover для Tooltip */}
            <span>
              <Button
                type="primary"
                icon={<PlusOutlined />}
                disabled={!!openVersion}
                loading={createDraft.isPending}
                onClick={() => createDraft.mutate()}
              >
                {t("codeset.createDraft")}
              </Button>
            </span>
          </Tooltip>
        }
      >
        <Loader {...versions}>
          {(items) => (
            <List
              dataSource={items}
              locale={{ emptyText: t("common.empty") }}
              renderItem={(v) => (
                <List.Item>
                  <List.Item.Meta
                    title={
                      <Space wrap>
                        <Link to={`/versions/${v.id}`}>
                          <Typography.Text strong>v{v.version}</Typography.Text>
                        </Link>
                        <StatusTag status={v.status} />
                        {v.id === openVersion?.id && <Tag color="blue">{t("codeset.openVersion")}</Tag>}
                      </Space>
                    }
                    description={
                      <>
                        <Typography.Text type="secondary">
                          {t("version.itemCount")}: {v.item_count ?? 0}
                        </Typography.Text>
                        {v.published_at && (
                          <Typography.Text type="secondary" style={{ marginLeft: 12 }}>
                            {t("version.publishedAt")}: {v.published_at}
                          </Typography.Text>
                        )}
                      </>
                    }
                  />
                </List.Item>
              )}
            />
          )}
        </Loader>
      </Card>

      <Modal
        title="Переименовать codeset"
        open={renameOpen}
        onCancel={() => setRenameOpen(false)}
        destroyOnClose
        onOk={() => renameForm.submit()}
        confirmLoading={rename.isPending}
      >
        <Form
          form={renameForm}
          layout="vertical"
          initialValues={{ new_name: codeset.data?.name ?? "" }}
          onFinish={(v) => rename.mutate(v.new_name)}
        >
          <Typography.Paragraph type="secondary">
            Старое имя сохраняется в aliases для ingestion-коннектора OM.
          </Typography.Paragraph>
          <Form.Item
            name="new_name"
            label="Новое name (snake_case)"
            rules={[
              { required: true },
              {
                pattern: /^[a-z][a-z0-9_]{0,63}$/,
                message: "lower snake_case, ≤64 chars",
              },
            ]}
          >
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
