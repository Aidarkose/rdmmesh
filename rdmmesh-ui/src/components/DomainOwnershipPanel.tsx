import { useState } from "react";
import {
  Alert,
  Button,
  Card,
  Form,
  List,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
  message,
} from "antd";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { adminApi, adminMutations, type AdminOwnershipView } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { UserPicker } from "./UserPicker";

interface Props {
  domainId: string;
}

const ROLES = ["OWNER", "STEWARD", "EXPERT", "APPROVER"] as const;

/**
 * E18.4 — Owners & Stewards для domain'а. Видна только под RDM_ADMIN
 * (родительский компонент скрывает её при отсутствии роли).
 *
 * Назначения, сделанные тут, идут с origin='RDM' и не перетираются
 * OM-webhook'ом. Флаг pinned_local дополнительно защищает строку
 * даже если OM пытается её обновить (ADR-0011, E18 §2.2).
 */
export function DomainOwnershipPanel({ domainId }: Props) {
  const queryClient = useQueryClient();
  const [form] = Form.useForm();
  const [selectedUser, setSelectedUser] = useState<string | null>(null);

  const list = useQuery({
    queryKey: qk.admin.domainOwnership(domainId),
    queryFn: () => adminApi.listDomainOwnership(domainId),
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: qk.admin.domainOwnership(domainId) });

  const assign = useMutation({
    mutationFn: (body: { om_user_id: string; role: string; pinned_local: boolean }) =>
      adminMutations.assignDomainOwnership(domainId, {
        om_user_id: body.om_user_id,
        role: body.role as "OWNER" | "STEWARD" | "EXPERT" | "APPROVER",
        pinned_local: body.pinned_local,
      }),
    onSuccess: () => {
      message.success("Назначение создано");
      form.resetFields();
      setSelectedUser(null);
      invalidate();
    },
    onError: (e: Error) => message.error(e.message),
  });

  const togglePin = useMutation({
    mutationFn: (r: AdminOwnershipView) =>
      adminMutations.patchOwnership(r.id, !r.pinned_local),
    onSuccess: invalidate,
    onError: (e: Error) => message.error(e.message),
  });

  const remove = useMutation({
    mutationFn: (id: string) => adminMutations.deleteOwnership(id),
    onSuccess: () => {
      message.success("Назначение удалено");
      invalidate();
    },
    onError: (e: Error) => message.error(e.message),
  });

  return (
    <Card title="Owners & Stewards (admin)" style={{ marginBottom: 16 }}>
      <Alert
        type="info"
        showIcon
        message="Локальные назначения (origin=RDM) не перетираются OM-webhook'ом. Pinned-флаг защищает даже OM-origin записи."
        style={{ marginBottom: 16 }}
      />

      <Form
        form={form}
        layout="inline"
        onFinish={(values) =>
          assign.mutate({
            om_user_id: values.user,
            role: values.role,
            pinned_local: values.pinned ?? false,
          })
        }
        style={{ marginBottom: 16 }}
      >
        <Form.Item name="user" label="Пользователь" rules={[{ required: true }]} style={{ minWidth: 320 }}>
          <UserPicker
            value={selectedUser}
            onChange={(v) => {
              setSelectedUser(v);
              form.setFieldValue("user", v);
            }}
            placeholder="Поиск по username / email…"
          />
        </Form.Item>
        <Form.Item name="role" label="Роль" rules={[{ required: true }]} initialValue="OWNER">
          <Select style={{ width: 140 }} options={ROLES.map((r) => ({ value: r, label: r }))} />
        </Form.Item>
        <Form.Item name="pinned" label="Pin" valuePropName="checked" initialValue={false}>
          <Switch />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={assign.isPending}>
            Назначить
          </Button>
        </Form.Item>
      </Form>

      <List
        loading={list.isLoading}
        dataSource={list.data ?? []}
        locale={{ emptyText: "Нет назначений" }}
        renderItem={(r) => (
          <List.Item
            actions={[
              <Switch
                key="pin"
                checked={r.pinned_local}
                checkedChildren="pinned"
                unCheckedChildren="pin"
                onChange={() => togglePin.mutate(r)}
                loading={togglePin.isPending}
              />,
              <Popconfirm
                key="del"
                title="Удалить назначение?"
                description={
                  r.origin === "OM"
                    ? "Запись пришла из OM. Удаление может быть восстановлено следующим webhook'ом, если не включён pin."
                    : undefined
                }
                onConfirm={() => remove.mutate(r.id)}
              >
                <Button danger size="small">
                  Удалить
                </Button>
              </Popconfirm>,
            ]}
          >
            <List.Item.Meta
              title={
                <Space>
                  <Tag color={r.role === "OWNER" ? "gold" : "blue"}>{r.role}</Tag>
                  <Tag color={r.origin === "OM" ? "geekblue" : "green"}>{r.origin}</Tag>
                  {r.pinned_local && <Tag color="purple">PIN</Tag>}
                  {r.is_provisional && <Tag>provisional</Tag>}
                  <Typography.Text code>{r.om_user_id}</Typography.Text>
                </Space>
              }
              description={
                <Typography.Text type="secondary">
                  Назначено {new Date(r.assigned_at).toLocaleString()}
                </Typography.Text>
              }
            />
          </List.Item>
        )}
      />
    </Card>
  );
}
