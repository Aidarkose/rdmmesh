import { useState } from "react";
import {
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  adminApi,
  adminMutations,
  type AdminDomainView,
  type CreateDomainRequest,
} from "@/api/endpoints";
import { qk } from "@/api/queryClient";

/**
 * E18.2 — главная страница admin'а: список всех доменов с фильтром по master,
 * кнопками Create / Edit / Rename / Delete / Link-to-OM / Unlink. Доступна
 * только пользователям с ролью RDM_ADMIN (route-gating на App-уровне).
 */
export function AdminDomainsPage() {
  const queryClient = useQueryClient();
  const list = useQuery({
    queryKey: qk.admin.domains(),
    queryFn: adminApi.listDomains,
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: qk.admin.domains() });

  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<AdminDomainView | null>(null);
  const [renameTarget, setRenameTarget] = useState<AdminDomainView | null>(null);
  const [linkTarget, setLinkTarget] = useState<AdminDomainView | null>(null);

  const remove = useMutation({
    mutationFn: (id: string) => adminMutations.deleteDomain(id),
    onSuccess: () => {
      message.success("Домен удалён");
      invalidate();
    },
    onError: (e: Error) => message.error(e.message),
  });

  const unlink = useMutation({
    mutationFn: (id: string) => adminMutations.unlinkDomainFromOm(id),
    onSuccess: () => {
      message.success("Отвязан от OM, master=RDM");
      invalidate();
    },
    onError: (e: Error) => message.error(e.message),
  });

  return (
    <Card
      title="Admin — Domains"
      extra={
        <Button type="primary" onClick={() => setCreateOpen(true)}>
          Создать домен
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        OM-домены приходят через webhook и здесь видны как read-only. RDM-локальные
        и LINKED-домены можно редактировать.
      </Typography.Paragraph>
      <Table
        rowKey="id"
        size="middle"
        loading={list.isLoading}
        dataSource={list.data ?? []}
        pagination={false}
        columns={[
          {
            title: "Name",
            dataIndex: "name",
            render: (n: string, r: AdminDomainView) => (
              <Link to={`/domains/${r.id}`}>{r.display_name ?? n}</Link>
            ),
          },
          { title: "Code", dataIndex: "name" },
          {
            title: "Master",
            dataIndex: "master",
            render: (m: string) => (
              <Tag color={m === "OM" ? "geekblue" : m === "LINKED" ? "purple" : "green"}>
                {m}
              </Tag>
            ),
          },
          {
            title: "om_domain_id",
            dataIndex: "om_domain_id",
            render: (v: string | null) =>
              v ? <Typography.Text code>{v.slice(0, 8)}…</Typography.Text> : "—",
          },
          { title: "Codesets", dataIndex: "active_codeset_count" },
          {
            title: "Actions",
            render: (_: unknown, r: AdminDomainView) => (
              <Space size="small">
                {r.master !== "OM" && (
                  <Button size="small" onClick={() => setEditTarget(r)}>
                    Edit
                  </Button>
                )}
                {r.master === "RDM" && (
                  <Button size="small" onClick={() => setRenameTarget(r)}>
                    Rename
                  </Button>
                )}
                {r.master === "RDM" && (
                  <Button size="small" onClick={() => setLinkTarget(r)}>
                    Link to OM
                  </Button>
                )}
                {r.master === "LINKED" && (
                  <Popconfirm
                    title="Отвязать от OM?"
                    description="Domain станет RDM-локальным; om_domain_id сохранится в external_refs.former_om."
                    onConfirm={() => unlink.mutate(r.id)}
                  >
                    <Button size="small">Unlink</Button>
                  </Popconfirm>
                )}
                {r.master === "RDM" && (
                  <Popconfirm
                    title="Удалить домен?"
                    description={
                      r.active_codeset_count > 0
                        ? `Имеется ${r.active_codeset_count} активных codeset'ов — удаление будет отклонено.`
                        : "Это необратимо."
                    }
                    onConfirm={() => remove.mutate(r.id)}
                  >
                    <Button size="small" danger>
                      Delete
                    </Button>
                  </Popconfirm>
                )}
              </Space>
            ),
          },
        ]}
      />

      <CreateDomainModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false);
          invalidate();
        }}
      />

      <EditDomainModal
        target={editTarget}
        onClose={() => setEditTarget(null)}
        onSaved={() => {
          setEditTarget(null);
          invalidate();
        }}
      />

      <RenameDomainModal
        target={renameTarget}
        onClose={() => setRenameTarget(null)}
        onSaved={() => {
          setRenameTarget(null);
          invalidate();
        }}
      />

      <LinkToOmModal
        target={linkTarget}
        onClose={() => setLinkTarget(null)}
        onSaved={() => {
          setLinkTarget(null);
          invalidate();
        }}
      />
    </Card>
  );
}

function CreateDomainModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [form] = Form.useForm();
  const create = useMutation({
    mutationFn: (body: CreateDomainRequest) => adminMutations.createDomain(body),
    onSuccess: () => {
      message.success("Домен создан");
      form.resetFields();
      onCreated();
    },
    onError: (e: Error) => message.error(e.message),
  });

  return (
    <Modal
      title="Создать домен"
      open={open}
      onCancel={onClose}
      destroyOnClose
      onOk={() => form.submit()}
      confirmLoading={create.isPending}
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={(v) =>
          create.mutate({
            name: v.name,
            om_domain_id: v.om_domain_id || null,
            display_name: v.display_name || null,
            description: v.description || null,
            label_ru: v.label_ru || null,
            label_en: v.label_en || null,
            tags: v.tags ? String(v.tags).split(",").map((s: string) => s.trim()).filter(Boolean) : null,
          })
        }
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
          <Input placeholder="treasury" />
        </Form.Item>
        <Form.Item name="display_name" label="Display name">
          <Input placeholder="Treasury Department" />
        </Form.Item>
        <Form.Item name="description" label="Description">
          <Input.TextArea rows={2} />
        </Form.Item>
        <Form.Item name="label_ru" label="Label (RU)">
          <Input />
        </Form.Item>
        <Form.Item name="label_en" label="Label (EN)">
          <Input />
        </Form.Item>
        <Form.Item name="tags" label="Tags (comma-separated)">
          <Input placeholder="risk, finance" />
        </Form.Item>
        <Form.Item
          name="om_domain_id"
          label="om_domain_id (опционально → LINKED)"
          extra="Если указан — domain создаётся как LINKED. Если нет — master=RDM."
        >
          <Input placeholder="UUID" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function EditDomainModal({
  target,
  onClose,
  onSaved,
}: {
  target: AdminDomainView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm();
  const patch = useMutation({
    mutationFn: (body: Parameters<typeof adminMutations.patchDomain>[1]) =>
      adminMutations.patchDomain(target!.id, body),
    onSuccess: () => {
      message.success("Сохранено");
      onSaved();
    },
    onError: (e: Error) => message.error(e.message),
  });

  return (
    <Modal
      title={target ? `Edit "${target.name}"` : ""}
      open={!!target}
      onCancel={onClose}
      destroyOnClose
      onOk={() => form.submit()}
      confirmLoading={patch.isPending}
    >
      {target && (
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            display_name: target.display_name ?? "",
            description: target.description ?? "",
            label_ru: target.label_ru ?? "",
            label_en: target.label_en ?? "",
            tags: target.tags.join(", "),
          }}
          onFinish={(v) =>
            patch.mutate({
              display_name: v.display_name || null,
              description: v.description || null,
              label_ru: v.label_ru || null,
              label_en: v.label_en || null,
              tags: v.tags ? String(v.tags).split(",").map((s: string) => s.trim()).filter(Boolean) : null,
            })
          }
        >
          {target.master === "LINKED" && (
            <Typography.Paragraph type="warning">
              Domain LINKED с OM — изменения в display_name / description /
              label_ru / label_en / tags переписываются в local_overrides
              (RDM перебивает OM для этих полей).
            </Typography.Paragraph>
          )}
          <Form.Item name="display_name" label="Display name">
            <Input />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="label_ru" label="Label (RU)">
            <Input />
          </Form.Item>
          <Form.Item name="label_en" label="Label (EN)">
            <Input />
          </Form.Item>
          <Form.Item name="tags" label="Tags (comma-separated)">
            <Input />
          </Form.Item>
        </Form>
      )}
    </Modal>
  );
}

function RenameDomainModal({
  target,
  onClose,
  onSaved,
}: {
  target: AdminDomainView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm();
  const rename = useMutation({
    mutationFn: (newName: string) => adminMutations.renameDomain(target!.id, newName),
    onSuccess: () => {
      message.success("Переименован");
      onSaved();
    },
    onError: (e: Error) => message.error(e.message),
  });

  return (
    <Modal
      title={target ? `Rename "${target.name}"` : ""}
      open={!!target}
      onCancel={onClose}
      destroyOnClose
      onOk={() => form.submit()}
      confirmLoading={rename.isPending}
    >
      {target && (
        <Form
          form={form}
          layout="vertical"
          initialValues={{ new_name: target.name }}
          onFinish={(v) => rename.mutate(v.new_name)}
        >
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
      )}
    </Modal>
  );
}

function LinkToOmModal({
  target,
  onClose,
  onSaved,
}: {
  target: AdminDomainView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm();
  const link = useMutation({
    mutationFn: (omId: string) => adminMutations.linkDomainToOm(target!.id, omId),
    onSuccess: () => {
      message.success("Слинкован с OM");
      onSaved();
    },
    onError: (e: Error) => message.error(e.message),
  });

  return (
    <Modal
      title={target ? `Link "${target.name}" to OM` : ""}
      open={!!target}
      onCancel={onClose}
      destroyOnClose
      onOk={() => form.submit()}
      confirmLoading={link.isPending}
    >
      {target && (
        <Form form={form} layout="vertical" onFinish={(v) => link.mutate(v.om_domain_id)}>
          <Typography.Paragraph type="secondary">
            Введите UUID существующего OM-домена. После линковки RDM-локальный
            становится LINKED; webhook'и для этого UUID будут применяться по
            field-level правилам (ADR-0011).
          </Typography.Paragraph>
          <Form.Item
            name="om_domain_id"
            label="om_domain_id (UUID)"
            rules={[
              { required: true },
              {
                pattern:
                  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
                message: "must be a UUID",
              },
            ]}
          >
            <Input placeholder="00000000-0000-0000-0000-000000000000" />
          </Form.Item>
        </Form>
      )}
    </Modal>
  );
}
