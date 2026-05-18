import { useState } from "react";
import { Button, Form, Input, Modal, Space, App as AntApp } from "antd";
import { ArrowRightOutlined, CheckCircleOutlined, RollbackOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiMutations, type TransitionRequest } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";
import type { CodeSetVersion, VersionStatus } from "@/api/types";

interface Props {
  version: CodeSetVersion;
}

// Какие действия доступны из какого статуса. Совпадает с матрицей StateMachine'а
// в backend (handoff E5 §1.3 / E6 §1.6). Backend всё равно валидирует — это hint UI.
//   - submit: DRAFT → IN_REVIEW
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

export function WorkflowActions({ version }: Props) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const [pending, setPending] = useState<{ action: Action; def: ActionDef } | null>(null);
  const [form] = Form.useForm<{ comment: string }>();

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
      form.resetFields();
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const actions = ACTIONS[version.status] ?? {};
  const entries = Object.entries(actions) as [Action, ActionDef][];

  if (entries.length === 0) {
    return null;
  }

  const onClick = (action: Action, def: ActionDef) => {
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
            loading={transition.isPending && (!pending || pending.action === action)}
          >
            {t(def.i18nKey)}
          </Button>
        ))}
      </Space>

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
