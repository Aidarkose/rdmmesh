import { useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  App as AntApp,
  type TableColumnsType,
} from "antd";
import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { api, apiMutations, type SubscriptionCreateRequest } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { ApiError } from "@/api/client";
import { Loader } from "@/components/Loader";
import { useAuth } from "@/auth/AuthContext";
import type { DeliveryStatus, Subscription, SubscriptionEvent, SubscriptionFilter } from "@/api/types";

const STATUS_COLOR: Record<DeliveryStatus, string> = {
  OK: "green",
  FAILED: "red",
  RETRYING: "orange",
};

const STATUS_BADGE: Record<DeliveryStatus, "success" | "error" | "warning"> = {
  OK: "success",
  FAILED: "error",
  RETRYING: "warning",
};

const EVENTS: SubscriptionEvent[] = ["VERSION_PUBLISHED", "VERSION_DEPRECATED"];

export function SubscriptionsPage() {
  const { t } = useTranslation();
  const { baseRoles } = useAuth();
  const subs = useApi(api.listSubscriptions, qk.subscriptions.all());
  const [createOpen, setCreateOpen] = useState(false);

  const isAdmin = baseRoles.includes("RDM_ADMIN");

  if (!isAdmin) {
    // Frontend-guard. Backend всё равно отдаст 403 — это просто понятный UX
    // если кто-то пришёл по прямой ссылке без admin-роли.
    return (
      <Alert
        type="warning"
        showIcon
        message={t("subs.notAdminTitle")}
        description={t("subs.notAdminDescription")}
      />
    );
  }

  return (
    <Card
      title={t("subs.title")}
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          {t("subs.create")}
        </Button>
      }
    >
      <Loader {...subs}>{(items) => <SubscriptionsTable items={items} />}</Loader>
      <CreateSubscriptionModal open={createOpen} onClose={() => setCreateOpen(false)} />
    </Card>
  );
}

function SubscriptionsTable({ items }: { items: Subscription[] }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();

  const remove = useMutation({
    mutationFn: (id: string) => apiMutations.deleteSubscription(id),
    onSuccess: () => {
      message.success(t("subs.deleteSuccess"));
      queryClient.invalidateQueries({ queryKey: qk.subscriptions.all() });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const columns: TableColumnsType<Subscription> = [
    {
      title: t("subs.col.id"),
      dataIndex: "id",
      key: "id",
      width: 100,
      render: (id: string) => (
        <Tooltip title={id}>
          <code>{id.slice(0, 8)}…</code>
        </Tooltip>
      ),
    },
    {
      title: t("subs.col.url"),
      dataIndex: "url",
      key: "url",
      ellipsis: true,
      render: (url: string) => (
        <Typography.Text copyable={{ text: url }} ellipsis>
          {url}
        </Typography.Text>
      ),
    },
    {
      title: t("subs.col.secretId"),
      dataIndex: "secret_id",
      key: "secret_id",
      width: 140,
      render: (s: string) => <code>{s}</code>,
    },
    {
      title: t("subs.col.filter"),
      dataIndex: "filter",
      key: "filter",
      render: (f: SubscriptionFilter) => <FilterBadge filter={f} />,
    },
    {
      title: t("subs.col.active"),
      dataIndex: "active",
      key: "active",
      width: 90,
      render: (a: boolean) =>
        a ? <Tag color="blue">{t("subs.active")}</Tag> : <Tag>{t("subs.inactive")}</Tag>,
    },
    {
      title: t("subs.col.lastDelivery"),
      key: "lastDelivery",
      width: 200,
      render: (_, row) => {
        if (!row.last_delivery_status) {
          return <Typography.Text type="secondary">{t("subs.neverDelivered")}</Typography.Text>;
        }
        return (
          <Space direction="vertical" size={0}>
            <Badge
              status={STATUS_BADGE[row.last_delivery_status as DeliveryStatus] ?? "default"}
              text={
                <Tag color={STATUS_COLOR[row.last_delivery_status as DeliveryStatus] ?? "default"}>
                  {row.last_delivery_status}
                </Tag>
              }
            />
            {row.last_delivery_at && (
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {row.last_delivery_at}
              </Typography.Text>
            )}
          </Space>
        );
      },
    },
    {
      title: t("subs.col.createdAt"),
      dataIndex: "created_at",
      key: "created_at",
      width: 200,
      render: (v: string | null | undefined) =>
        v ? <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text> : "—",
    },
    {
      title: t("items.actions"),
      key: "actions",
      fixed: "right",
      width: 120,
      render: (_, row) => (
        <Popconfirm
          title={t("subs.deleteConfirmTitle")}
          description={t("subs.deleteConfirmDescription")}
          okText={t("version.deleteConfirmOk")}
          okButtonProps={{ danger: true }}
          cancelText={t("workflow.modal.cancel")}
          onConfirm={() => remove.mutate(row.id)}
          disabled={!row.active}
        >
          <Tooltip title={row.active ? t("subs.deactivate") : t("subs.alreadyInactive")}>
            <Button danger size="small" icon={<DeleteOutlined />} disabled={!row.active}>
              {t("subs.deactivate")}
            </Button>
          </Tooltip>
        </Popconfirm>
      ),
    },
  ];

  return (
    <Table<Subscription>
      rowKey={(r) => r.id}
      dataSource={items}
      columns={columns}
      size="small"
      pagination={false}
      scroll={{ x: "max-content" }}
      rowClassName={(r) => (r.active ? "" : "subs-row-inactive")}
    />
  );
}

function FilterBadge({ filter }: { filter: SubscriptionFilter }) {
  const { t } = useTranslation();
  const empty =
    !filter ||
    ((!filter.domains || filter.domains.length === 0) &&
      (!filter.codesets || filter.codesets.length === 0) &&
      (!filter.events || filter.events.length === 0));
  if (empty) return <Tag>{t("subs.filterAll")}</Tag>;
  return (
    <Space size={[4, 4]} wrap>
      {filter.domains?.map((d) => (
        <Tag key={`d-${d}`} color="cyan">
          d:{d}
        </Tag>
      ))}
      {filter.codesets?.map((c) => (
        <Tag key={`c-${c}`} color="geekblue">
          c:{c}
        </Tag>
      ))}
      {filter.events?.map((e) => (
        <Tag key={`e-${e}`} color="purple">
          {e}
        </Tag>
      ))}
    </Space>
  );
}

interface CreateFormValues {
  url: string;
  secretId: string;
  domains?: string[];
  codesets?: string[];
  events?: SubscriptionEvent[];
  active: boolean;
}

function CreateSubscriptionModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<CreateFormValues>();

  const create = useMutation({
    mutationFn: (body: SubscriptionCreateRequest) => apiMutations.createSubscription(body),
    onSuccess: () => {
      message.success(t("subs.createSuccess"));
      queryClient.invalidateQueries({ queryKey: qk.subscriptions.all() });
      form.resetFields();
      onClose();
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const onSubmit = async () => {
    let values: CreateFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const filter: SubscriptionFilter = {
      domains: values.domains?.length ? values.domains : null,
      codesets: values.codesets?.length ? values.codesets : null,
      events: values.events?.length ? values.events : null,
    };
    create.mutate({
      url: values.url.trim(),
      secret_id: values.secretId.trim(),
      filter,
      active: values.active,
    });
  };

  return (
    <Modal
      open={open}
      title={t("subs.createTitle")}
      okText={t("subs.create")}
      cancelText={t("workflow.modal.cancel")}
      confirmLoading={create.isPending}
      onOk={() => void onSubmit()}
      onCancel={() => {
        form.resetFields();
        onClose();
      }}
      destroyOnClose
      width={640}
    >
      <Form form={form} layout="vertical" preserve={false} initialValues={{ active: true }}>
        <Form.Item
          label={t("subs.fld.url")}
          name="url"
          rules={[
            { required: true, message: t("subs.fld.urlRequired") },
            {
              type: "url",
              message: t("subs.fld.urlInvalid"),
            },
          ]}
          extra={t("subs.fld.urlHint")}
        >
          <Input placeholder="https://consumer.bank/webhooks/rdm" />
        </Form.Item>

        <Form.Item
          label={t("subs.fld.secretId")}
          name="secretId"
          rules={[{ required: true, message: t("subs.fld.secretIdRequired") }]}
          extra={t("subs.fld.secretIdHint")}
        >
          <Input placeholder="primary" />
        </Form.Item>

        <Form.Item label={t("subs.fld.filter")} extra={t("subs.fld.filterHint")}>
          <Space direction="vertical" style={{ width: "100%" }}>
            <Form.Item name="domains" noStyle>
              <Select
                mode="tags"
                placeholder={t("subs.fld.domains")}
                tokenSeparators={[",", " "]}
              />
            </Form.Item>
            <Form.Item name="codesets" noStyle>
              <Select
                mode="tags"
                placeholder={t("subs.fld.codesets")}
                tokenSeparators={[",", " "]}
              />
            </Form.Item>
            <Form.Item name="events" noStyle>
              <Select
                mode="multiple"
                placeholder={t("subs.fld.events")}
                options={EVENTS.map((e) => ({ value: e, label: e }))}
              />
            </Form.Item>
          </Space>
        </Form.Item>

        <Form.Item
          label={t("subs.fld.active")}
          name="active"
          valuePropName="checked"
          extra={t("subs.fld.activeHint")}
        >
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
