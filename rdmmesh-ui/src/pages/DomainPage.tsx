import { useState } from "react";
import {
  Breadcrumb,
  Button,
  Card,
  Form,
  Input,
  List,
  Modal,
  Select,
  Tag,
  Typography,
  message,
} from "antd";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { api, apiMutations, type CreateCodeSetRequest } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { useAuth } from "@/auth/AuthContext";
import { Loader } from "@/components/Loader";
import { DomainOwnershipPanel } from "@/components/DomainOwnershipPanel";
import { DomainApproversPanel } from "@/components/DomainApproversPanel";

export function DomainPage() {
  const { t } = useTranslation();
  const { domainId } = useParams<{ domainId: string }>();
  const id = domainId!;
  const { baseRoles } = useAuth();
  const isAdmin = baseRoles.includes("RDM_ADMIN");
  const canCreateCodeset =
    isAdmin ||
    baseRoles.includes("RDM_AUTHOR") ||
    baseRoles.includes("RDM_SCHEMA_DESIGNER");

  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();

  const domain = useApi(() => api.getDomain(id), qk.domains.one(id));
  const codesets = useApi(() => api.listCodeSetsByDomain(id), qk.codesets.byDomain(id));

  const createCodeSet = useMutation({
    mutationFn: (body: CreateCodeSetRequest) => apiMutations.createCodeSet(id, body),
    onSuccess: () => {
      message.success("Справочник создан");
      setCreateOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: qk.codesets.byDomain(id) });
    },
    onError: (e: Error) => message.error(e.message),
  });

  return (
    <>
      <Breadcrumb
        style={{ marginBottom: 16 }}
        items={[
          { title: <Link to="/catalog">{t("nav.catalog")}</Link> },
          { title: domain.data?.display_name ?? domain.data?.name ?? "..." },
        ]}
      />
      <Card title={t("catalog.domain")} style={{ marginBottom: 16 }}>
        <Loader {...domain}>
          {(d) => (
            <>
              <Typography.Title level={4} style={{ marginTop: 0 }}>
                {d.display_name ?? d.name}
              </Typography.Title>
              <Typography.Text type="secondary">{d.name}</Typography.Text>
              {d.description && (
                <Typography.Paragraph style={{ marginTop: 12 }}>
                  {d.description}
                </Typography.Paragraph>
              )}
              {d.tags && d.tags.length > 0 && (
                <div style={{ marginTop: 12 }}>
                  {d.tags.map((tag) => (
                    <Tag key={tag}>{tag}</Tag>
                  ))}
                </div>
              )}
            </>
          )}
        </Loader>
      </Card>

      {isAdmin && <DomainOwnershipPanel domainId={id} />}
      {isAdmin && <DomainApproversPanel domainId={id} />}

      <Card
        title={t("catalog.codesets")}
        extra={
          canCreateCodeset && (
            <Button type="primary" onClick={() => setCreateOpen(true)}>
              Создать справочник
            </Button>
          )
        }
      >
        <Loader {...codesets}>
          {(items) => (
            <List
              dataSource={items}
              locale={{ emptyText: t("common.empty") }}
              renderItem={(c) => (
                <List.Item>
                  <List.Item.Meta
                    title={
                      <Link to={`/codesets/${c.id}`}>
                        <Typography.Text strong>{c.display_name ?? c.name}</Typography.Text>
                      </Link>
                    }
                    description={
                      <>
                        <Typography.Text type="secondary">{c.name}</Typography.Text>
                        {c.current_published_version ? (
                          <Tag color="green" style={{ marginLeft: 8 }}>
                            v{c.current_published_version}
                          </Tag>
                        ) : (
                          <Tag style={{ marginLeft: 8 }}>{t("catalog.noPublished")}</Tag>
                        )}
                        <Tag style={{ marginLeft: 8 }}>{c.hierarchy_mode}</Tag>
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
        title="Создать справочник"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
        onOk={() => form.submit()}
        confirmLoading={createCodeSet.isPending}
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ hierarchy_mode: "NONE", key_preset: "single" }}
          onFinish={(v) => {
            let keySpec: Record<string, unknown> | null = null;
            let schema: Record<string, unknown> | null = null;
            // E20 — display-only ссылка на словарь меток для оси матрицы.
            // Прикручивается к двум первым частям ключа (from_*/to_*) только если
            // пользователь явно выбрал словарь в селекте.
            const labelRef = v.label_dict_codeset_id
              ? { codeset_id: v.label_dict_codeset_id as string }
              : null;
            if (v.key_preset === "transition_matrix") {
              keySpec = {
                parts: [
                  {
                    name: "from_rating",
                    type: "STRING",
                    ...(labelRef ? { label_codeset_ref: labelRef } : {}),
                  },
                  {
                    name: "to_rating",
                    type: "STRING",
                    ...(labelRef ? { label_codeset_ref: labelRef } : {}),
                  },
                  {
                    name: "horizon",
                    type: "ENUM",
                    allowed_values: ["1M", "3M", "6M", "1Y", "3Y", "5Y"],
                  },
                ],
              };
              schema = {
                type: "object",
                required: ["probability"],
                properties: {
                  probability: { type: "number", minimum: 0, maximum: 1 },
                },
              };
            } else if (v.key_preset === "delinquency_matrix") {
              keySpec = {
                parts: [
                  {
                    name: "from_bucket",
                    type: "STRING",
                    ...(labelRef ? { label_codeset_ref: labelRef } : {}),
                  },
                  {
                    name: "to_bucket",
                    type: "STRING",
                    ...(labelRef ? { label_codeset_ref: labelRef } : {}),
                  },
                  {
                    name: "period",
                    type: "ENUM",
                    allowed_values: ["1M", "3M", "6M", "1Y"],
                  },
                ],
              };
              schema = {
                type: "object",
                required: ["probability"],
                properties: {
                  probability: { type: "number", minimum: 0, maximum: 1 },
                },
              };
            } else if (v.key_preset === "custom" && v.key_spec_json) {
              try {
                keySpec = JSON.parse(v.key_spec_json);
              } catch {
                message.error("key_spec: невалидный JSON");
                return;
              }
            }
            createCodeSet.mutate({
              name: v.name,
              display_name: v.display_name || null,
              description: v.description || null,
              hierarchy_mode: v.hierarchy_mode,
              key_spec: keySpec,
              initial_schema: schema,
            });
          }}
        >
          <Form.Item
            name="name"
            label="Code (snake_case)"
            rules={[
              { required: true, message: "Required" },
              {
                pattern: /^[a-z][a-z0-9_]{0,63}$/,
                message: "lower snake_case, ≤64 chars",
              },
            ]}
          >
            <Input placeholder="ifrs9_stages" />
          </Form.Item>
          <Form.Item name="display_name" label="Display name">
            <Input placeholder="IFRS9 Stages" />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="hierarchy_mode" label="Hierarchy">
            <Select
              options={[
                { value: "NONE", label: "NONE (плоский)" },
                { value: "INTRA_CODESET", label: "INTRA_CODESET (дерево внутри)" },
                { value: "CROSS_CODESET", label: "CROSS_CODESET (ссылки между справочниками)" },
              ]}
            />
          </Form.Item>
          <Form.Item name="key_preset" label="Ключ справочника">
            <Select
              options={[
                { value: "single", label: "Одиночный код (по умолчанию)" },
                {
                  value: "transition_matrix",
                  label: "Матрица миграций (from_rating, to_rating, horizon)",
                },
                {
                  value: "delinquency_matrix",
                  label:
                    "Матрица просроченной задолженности — DPD (from_bucket, to_bucket, period)",
                },
                { value: "custom", label: "Custom (JSON)" },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(prev, cur) => prev.key_preset !== cur.key_preset}>
            {({ getFieldValue }) => {
              const preset = getFieldValue("key_preset");
              if (preset === "custom") {
                return (
                  <Form.Item
                    name="key_spec_json"
                    label="key_spec (JSON)"
                    rules={[{ required: true, message: "Required" }]}
                  >
                    <Input.TextArea
                      rows={5}
                      placeholder={'{"parts":[{"name":"code","type":"STRING"}]}'}
                      style={{ fontFamily: "monospace", fontSize: 12 }}
                    />
                  </Form.Item>
                );
              }
              // E20 — словарь меток для осей from/to у матриц.
              // Видим только когда preset матричный. Кандидаты — одноключевые
              // CodeSet'ы текущего домена (key_spec.parts.length === 1).
              if (preset === "transition_matrix" || preset === "delinquency_matrix") {
                const dictCandidates = (codesets.data ?? []).filter(
                  (c) => (c.key_spec?.parts?.length ?? 0) === 1,
                );
                return (
                  <Form.Item
                    name="label_dict_codeset_id"
                    label="Словарь меток для осей from/to (опционально)"
                    extra="Одноключевой справочник того же домена; UI подставит «код — label» в заголовках матрицы. Backend ссылку не валидирует."
                  >
                    <Select<string>
                      allowClear
                      placeholder="— не использовать —"
                      options={dictCandidates.map((c) => ({
                        value: c.id,
                        label: `${c.display_name ?? c.name} (${c.name})`,
                      }))}
                    />
                  </Form.Item>
                );
              }
              return null;
            }}
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
