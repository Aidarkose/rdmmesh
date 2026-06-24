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
  Tag,
  Typography,
  message,
} from "antd";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { adminApi, adminMutations, type AdminUserView } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import type { Approver, DirectoryRole } from "@/api/types";
import { UserPicker } from "./UserPicker";

interface Props {
  domainId: string;
}

const ROLE_OPTIONS: { value: DirectoryRole; label: string }[] = [
  { value: "STEWARD", label: "Стюард (STEWARD)" },
  { value: "BUSINESS_OWNER", label: "Бизнес-владелец (BUSINESS_OWNER)" },
];

const roleLabel = (r: DirectoryRole) =>
  r === "STEWARD" ? "Стюард" : "Бизнес-владелец";

/**
 * Назначение согласующих домена (справочник ролей домена, BR-21/BR-22).
 * Видна только под RDM_ADMIN. Пишет в directory адресно по domain_id
 * (source RDM_ADMIN_LOCAL) — работает и для локальных доменов без om_domain_id,
 * для которых глобальный reload-сид кандидатов не заводит. Эти кандидаты затем
 * доступны автору в submit-диалоге (выбор стюарда/бизнес-владельца).
 */
export function DomainApproversPanel({ domainId }: Props) {
  const queryClient = useQueryClient();
  const [form] = Form.useForm();
  const [picked, setPicked] = useState<AdminUserView | null>(null);

  const list = useQuery({
    queryKey: qk.admin.domainApprovers(domainId),
    queryFn: () => adminApi.listDomainApprovers(domainId),
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: qk.admin.domainApprovers(domainId) });
    // submit-диалог читает кандидатов через qk.domains.approvers(domainId, role)
    queryClient.invalidateQueries({ queryKey: ["domains", domainId, "approvers"] });
  };

  const add = useMutation({
    mutationFn: (body: { role: DirectoryRole; user: AdminUserView }) =>
      adminMutations.addDomainApprover(domainId, {
        role: body.role,
        om_user_id: body.user.om_user_id,
        username: body.user.username,
        display_name: body.user.display_name,
      }),
    onSuccess: () => {
      message.success("Согласующий добавлен");
      form.resetFields();
      setPicked(null);
      invalidate();
    },
    onError: (e: Error) => message.error(e.message),
  });

  const remove = useMutation({
    mutationFn: (r: Approver) =>
      adminMutations.removeDomainApprover(domainId, r.role, r.omUserId),
    onSuccess: () => {
      message.success("Согласующий удалён");
      invalidate();
    },
    onError: (e: Error) => message.error(e.message),
  });

  return (
    <Card title="Согласующие домена (admin)" style={{ marginBottom: 16 }}>
      <Alert
        type="info"
        showIcon
        message="Кандидаты на стюарда и бизнес-владельца для submit-диалога. Добавляются адресно к этому домену (origin RDM_ADMIN_LOCAL); работает и для локальных доменов без связи с OpenMetadata."
        style={{ marginBottom: 16 }}
      />

      <Form
        form={form}
        layout="inline"
        onFinish={(values) => {
          if (!picked) {
            message.error("Выберите пользователя");
            return;
          }
          add.mutate({ role: values.role, user: picked });
        }}
        style={{ marginBottom: 16 }}
      >
        <Form.Item
          name="user"
          label="Пользователь"
          rules={[{ required: true, message: "Выберите пользователя" }]}
          style={{ minWidth: 320 }}
        >
          <UserPicker
            value={picked?.om_user_id ?? null}
            onChange={(v) => form.setFieldValue("user", v)}
            onPick={setPicked}
            placeholder="Поиск по username / email…"
          />
        </Form.Item>
        <Form.Item name="role" label="Роль" rules={[{ required: true }]} initialValue="STEWARD">
          <Select style={{ width: 240 }} options={ROLE_OPTIONS} />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={add.isPending}>
            Назначить
          </Button>
        </Form.Item>
      </Form>

      <List
        loading={list.isLoading}
        dataSource={list.data ?? []}
        locale={{ emptyText: "Согласующие не назначены" }}
        renderItem={(r) => (
          <List.Item
            actions={[
              <Popconfirm
                key="del"
                title="Убрать согласующего?"
                onConfirm={() => remove.mutate(r)}
              >
                <Button danger size="small" loading={remove.isPending}>
                  Удалить
                </Button>
              </Popconfirm>,
            ]}
          >
            <List.Item.Meta
              title={
                <Space wrap>
                  <Tag color={r.role === "STEWARD" ? "blue" : "gold"}>{roleLabel(r.role)}</Tag>
                  <Typography.Text strong>{r.displayName ?? r.username}</Typography.Text>
                  <Typography.Text type="secondary">({r.username})</Typography.Text>
                </Space>
              }
            />
          </List.Item>
        )}
      />
    </Card>
  );
}
