import { useState } from "react";
import { Alert, Button, Form, Input, Modal, Select, Space, App as AntApp } from "antd";
import { ArrowRightOutlined, CheckCircleOutlined, RollbackOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, apiMutations, type TransitionRequest } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";
import type { Approver, CodeSetVersion, VersionStatus } from "@/api/types";

interface Props {
  version: CodeSetVersion;
  // E17 / BR-21: домен CodeSet'а — нужен для submit-диалога (выбор
  // согласующих из справочника ролей домена). VersionPage его уже грузит.
  domainId?: string | null;
}

// Какие действия доступны из какого статуса. Совпадает с матрицей StateMachine'а
// в backend (handoff E5 §1.3 / E6 §1.6). Backend всё равно валидирует — это hint UI.
//   - submit: DRAFT → IN_REVIEW (E17: с выбором согласующих)
//   - steward_approve: IN_REVIEW → STEWARD_APPROVED
//   - steward_reject: IN_REVIEW → DRAFT (comment обязателен)
//   - owner_approve: STEWARD_APPROVED → OWNER_APPROVED (backend сразу auto-publish'ит)
//   - owner_reject: STEWARD_APPROVED → DRAFT (comment обязателен)
type Action = "submit" | "steward_approve" | "steward_reject" | "owner_approve" | "owner_reject";

interface ActionDef {
  to: VersionStatus;
  needsComment: boolean;
  icon: React.ReactNode;
  i18nKey: string;
  variant?: "primary" | "default" | "danger";
}

const ACTIONS: Partial<Record<VersionStatus, Partial<Record<Action, ActionDef>>>> = {
  DRAFT: {
    submit: {
      to: "IN_REVIEW",
      needsComment: false,
      icon: <ArrowRightOutlined />,
      i18nKey: "workflow.action.submit",
      variant: "primary",
    },
  },
  IN_REVIEW: {
    steward_approve: {
      to: "STEWARD_APPROVED",
      needsComment: false,
      icon: <CheckCircleOutlined />,
      i18nKey: "workflow.action.stewardApprove",
      variant: "primary",
    },
    steward_reject: {
      to: "DRAFT",
      needsComment: true,
      icon: <RollbackOutlined />,
      i18nKey: "workflow.action.stewardReject",
      variant: "danger",
    },
  },
  STEWARD_APPROVED: {
    owner_approve: {
      to: "OWNER_APPROVED",
      needsComment: false,
      icon: <CheckCircleOutlined />,
      i18nKey: "workflow.action.ownerApprove",
      variant: "primary",
    },
    owner_reject: {
      to: "DRAFT",
      needsComment: true,
      icon: <RollbackOutlined />,
      i18nKey: "workflow.action.ownerReject",
      variant: "danger",
    },
  },
  // OWNER_APPROVED → автоматический publish backend'ом, UI не показывает кнопок.
  // PUBLISHED/DEPRECATED/REJECTED — terminal, кнопок нет.
};

function approverLabel(a: Approver): string {
  return a.displayName ? `${a.displayName} (${a.username})` : a.username;
}

export function WorkflowActions({ version, domainId }: Props) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const [pending, setPending] = useState<{ action: Action; def: ActionDef } | null>(null);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [form] = Form.useForm<{ comment: string }>();
  const [submitForm] = Form.useForm<{
    steward_om_user_id: string;
    owner_om_user_id: string;
    comment?: string;
  }>();

  const transition = useMutation({
    mutationFn: (req: TransitionRequest) => apiMutations.transition(version.id, req),
    onSuccess: () => {
      message.success(t("workflow.success"));
      // Invalidate всё, что мог поменять transition: версия, история, мои задачи,
      // плюс список версий в этом codeset (статус мог поменяться, item_count — нет).
      queryClient.invalidateQueries({ queryKey: qk.versions.one(version.id) });
      queryClient.invalidateQueries({ queryKey: qk.versions.history(version.id) });
      queryClient.invalidateQueries({ queryKey: qk.versions.byCodeset(version.codeset_id) });
      queryClient.invalidateQueries({ queryKey: qk.codesets.one(version.codeset_id) });
      queryClient.invalidateQueries({ queryKey: qk.tasks.my() });
      setPending(null);
      setSubmitOpen(false);
      form.resetFields();
      submitForm.resetFields();
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  // E17 / BR-21: кандидаты-согласующие домена. Грузим лениво — только когда
  // открыт submit-диалог и известен domainId.
  const stewards = useQuery({
    queryKey: qk.domains.approvers(domainId ?? "_pending", "STEWARD"),
    queryFn: () => api.listApprovers(domainId as string, "STEWARD"),
    enabled: submitOpen && !!domainId,
  });
  const owners = useQuery({
    queryKey: qk.domains.approvers(domainId ?? "_pending", "BUSINESS_OWNER"),
    queryFn: () => api.listApprovers(domainId as string, "BUSINESS_OWNER"),
    enabled: submitOpen && !!domainId,
  });

  const actions = ACTIONS[version.status] ?? {};
  const entries = Object.entries(actions) as [Action, ActionDef][];

  if (entries.length === 0) {
    return null;
  }

  const onClick = (action: Action, def: ActionDef) => {
    if (action === "submit") {
      setSubmitOpen(true);
      return;
    }
    if (def.needsComment) {
      setPending({ action, def });
      return;
    }
    transition.mutate({ to: def.to, expected_status: version.status });
  };

  const onSubmitComment = async () => {
    const values = await form.validateFields();
    if (!pending) return;
    transition.mutate({
      to: pending.def.to,
      comment: values.comment.trim(),
      expected_status: version.status,
    });
  };

  const onSubmitForApproval = async () => {
    const values = await submitForm.validateFields();
    transition.mutate({
      to: "IN_REVIEW",
      expected_status: version.status,
      comment: values.comment?.trim() || undefined,
      assignee: {
        domain_id: domainId as string,
        steward_om_user_id: values.steward_om_user_id,
        owner_om_user_id: values.owner_om_user_id,
      },
    });
  };

  const toOptions = (list?: Approver[]) =>
    (list ?? []).map((a) => ({ value: a.omUserId, label: approverLabel(a) }));

  return (
    <>
      <Space wrap>
        {entries.map(([action, def]) => (
          <Button
            key={action}
            type={def.variant === "primary" ? "primary" : "default"}
            danger={def.variant === "danger"}
            icon={def.icon}
            onClick={() => onClick(action, def)}
            loading={
              transition.isPending &&
              (action === "submit"
                ? submitOpen
                : !pending || pending.action === action)
            }
          >
            {t(def.i18nKey)}
          </Button>
        ))}
      </Space>

      {/* E17 / BR-21: submit с выбором согласующих (домен → steward + business owner). */}
      <Modal
        open={submitOpen}
        title={t("workflow.submit.title")}
        okText={t("workflow.submit.ok")}
        cancelText={t("workflow.modal.cancel")}
        confirmLoading={transition.isPending}
        okButtonProps={{ disabled: !domainId }}
        onOk={() => void onSubmitForApproval()}
        onCancel={() => {
          setSubmitOpen(false);
          submitForm.resetFields();
        }}
        destroyOnClose
      >
        {!domainId ? (
          <Alert type="warning" showIcon message={t("workflow.submit.noDomain")} />
        ) : (
          <Form form={submitForm} layout="vertical" preserve={false}>
            <Form.Item label={t("workflow.submit.domain")}>
              <Input value={domainId} disabled />
            </Form.Item>
            <Form.Item
              label={t("workflow.submit.steward")}
              name="steward_om_user_id"
              rules={[{ required: true, message: t("workflow.submit.required") }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                loading={stewards.isLoading}
                placeholder={t("workflow.submit.stewardPlaceholder")}
                options={toOptions(stewards.data)}
                notFoundContent={
                  stewards.isLoading ? null : t("workflow.submit.empty")
                }
              />
            </Form.Item>
            <Form.Item
              label={t("workflow.submit.owner")}
              name="owner_om_user_id"
              rules={[{ required: true, message: t("workflow.submit.required") }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                loading={owners.isLoading}
                placeholder={t("workflow.submit.ownerPlaceholder")}
                options={toOptions(owners.data)}
                notFoundContent={owners.isLoading ? null : t("workflow.submit.empty")}
              />
            </Form.Item>
            <Form.Item label={t("workflow.submit.commentLabel")} name="comment">
              <Input.TextArea
                rows={3}
                maxLength={1000}
                showCount
                placeholder={t("workflow.submit.commentPlaceholder")}
              />
            </Form.Item>
          </Form>
        )}
      </Modal>

      <Modal
        open={!!pending}
        title={pending ? t(pending.def.i18nKey) : ""}
        okText={t("workflow.modal.ok")}
        cancelText={t("workflow.modal.cancel")}
        confirmLoading={transition.isPending}
        onOk={() => void onSubmitComment()}
        onCancel={() => {
          setPending(null);
          form.resetFields();
        }}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            label={t("workflow.modal.commentLabel")}
            name="comment"
            rules={[
              { required: true, message: t("workflow.modal.commentRequired") },
              { whitespace: true, message: t("workflow.modal.commentRequired") },
              { min: 3, message: t("workflow.modal.commentTooShort") },
            ]}
          >
            <Input.TextArea
              rows={4}
              placeholder={t("workflow.modal.commentPlaceholder")}
              maxLength={1000}
              showCount
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
