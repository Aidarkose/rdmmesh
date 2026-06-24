import { useState } from "react";
import {
  App as AntApp,
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  Modal,
  Segmented,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnsType } from "antd/es/table";

import { adminApi, adminMutations, type DeletionRequestStatus, type DeletionRequestView } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

const STATUS_OPTIONS: DeletionRequestStatus[] = [
  "PENDING",
  "APPROVED",
  "REJECTED",
  "CANCELLED",
];

const STATUS_COLOR: Record<DeletionRequestStatus, string> = {
  PENDING: "orange",
  APPROVED: "green",
  REJECTED: "red",
  CANCELLED: "default",
};

interface ApproveDialogState {
  open: boolean;
  request: DeletionRequestView | null;
}
interface RejectDialogState {
  open: boolean;
  request: DeletionRequestView | null;
}

/**
 * E22 — admin queue заявок на удаление CodeSet'а.
 *
 * <p>Фильтр по статусу (default PENDING). Approve открывает confirm-modal,
 * с дополнительным `force_archive` чекбоксом если у CodeSet'а есть PUBLISHED-версии.
 * Reject — отдельный modal с обязательным comment.
 */
export function AdminDeletionRequestsPage() {
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();
  const [statusFilter, setStatusFilter] = useState<DeletionRequestStatus>("PENDING");
  const [approveDialog, setApproveDialog] = useState<ApproveDialogState>({
    open: false,
    request: null,
  });
  const [approveForm] = Form.useForm<{
    decision_comment?: string;
    force_archive?: boolean;
  }>();
  const [rejectDialog, setRejectDialog] = useState<RejectDialogState>({
    open: false,
    request: null,
  });
  const [rejectForm] = Form.useForm<{ decision_comment: string }>();

  const list = useQuery({
    queryKey: qk.admin.deletionRequests(statusFilter),
    queryFn: () => adminApi.listDeletionRequests(statusFilter),
  });

  const approve = useMutation({
    mutationFn: ({
      id,
      decisionComment,
      forceArchive,
    }: {
      id: string;
      decisionComment?: string | null;
      forceArchive: boolean;
    }) =>
      adminMutations.approveDeletionRequest(id, {
        decision_comment: decisionComment ?? null,
        force_archive: forceArchive,
      }),
    onSuccess: () => {
      message.success(t("deletionRequest.admin.approveSuccess"));
      setApproveDialog({ open: false, request: null });
      approveForm.resetFields();
      // Все фильтры могут поменяться (заявка ушла из PENDING) — инвалидируем дерево.
      queryClient.invalidateQueries({ queryKey: ["admin", "deletion-requests"] });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const reject = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment: string }) =>
      adminMutations.rejectDeletionRequest(id, comment),
    onSuccess: () => {
      message.success(t("deletionRequest.admin.rejectSuccess"));
      setRejectDialog({ open: false, request: null });
      rejectForm.resetFields();
      queryClient.invalidateQueries({ queryKey: ["admin", "deletion-requests"] });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const columns: ColumnsType<DeletionRequestView> = [
    {
      title: t("deletionRequest.admin.col.domain"),
      key: "domain",
      render: (_, r) => (
        <Link to={`/domains/${r.domain_id}`}>{r.domain_name}</Link>
      ),
    },
    {
      title: t("deletionRequest.admin.col.codeset"),
      key: "codeset",
      render: (_, r) => (
        <Space>
          <Link to={`/codesets/${r.codeset_id}`}>{r.codeset_name}</Link>
          {r.codeset_deleted && (
            <Tag color="default">{t("deletionRequest.admin.alreadyDeleted")}</Tag>
          )}
          {r.has_published_versions && (
            <Tooltip title={t("deletionRequest.admin.publishedHint")}>
              <Tag color="blue">{t("deletionRequest.admin.published")}</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: t("deletionRequest.admin.col.reason"),
      dataIndex: "reason",
      key: "reason",
      render: (text: string) => (
        <Typography.Paragraph
          style={{ marginBottom: 0, maxWidth: 360 }}
          ellipsis={{ rows: 3, expandable: true, symbol: t("common.more") }}
        >
          {text}
        </Typography.Paragraph>
      ),
    },
    {
      title: t("deletionRequest.admin.col.requestedBy"),
      key: "requestedBy",
      render: (_, r) => (
        <Typography.Text>
          {r.requested_by_username ?? r.requested_by.substring(0, 8) + "…"}
        </Typography.Text>
      ),
    },
    {
      title: t("deletionRequest.admin.col.createdAt"),
      dataIndex: "created_at",
      key: "created_at",
      render: (s: string) => new Date(s).toLocaleString(),
    },
    {
      title: t("deletionRequest.admin.col.status"),
      dataIndex: "status",
      key: "status",
      render: (s: DeletionRequestStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag>,
    },
    {
      title: t("deletionRequest.admin.col.decision"),
      key: "decision",
      render: (_, r) =>
        r.status === "PENDING" ? (
          <Space>
            <Button
              type="primary"
              danger
              size="small"
              onClick={() => setApproveDialog({ open: true, request: r })}
            >
              {t("deletionRequest.admin.approve")}
            </Button>
            <Button
              size="small"
              onClick={() => setRejectDialog({ open: true, request: r })}
            >
              {t("deletionRequest.admin.reject")}
            </Button>
          </Space>
        ) : (
          <Tooltip
            title={r.decision_comment ?? ""}
          >
            <Typography.Text type="secondary">
              {r.decided_by_username ?? r.decided_by?.substring(0, 8) + "…"}
              {r.decided_at && (
                <span style={{ marginLeft: 8 }}>
                  · {new Date(r.decided_at).toLocaleString()}
                </span>
              )}
            </Typography.Text>
          </Tooltip>
        ),
    },
  ];

  return (
    <>
      <Card title={t("deletionRequest.admin.title")}>
        <Space style={{ marginBottom: 16 }}>
          <Segmented
            value={statusFilter}
            onChange={(v) => setStatusFilter(v as DeletionRequestStatus)}
            options={STATUS_OPTIONS.map((s) => ({
              label: s,
              value: s,
            }))}
          />
        </Space>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={list.data ?? []}
          loading={list.isPending}
          pagination={{ pageSize: 20 }}
          locale={{ emptyText: t("common.empty") }}
        />
      </Card>

      <Modal
        title={t("deletionRequest.admin.approveTitle")}
        open={approveDialog.open}
        onCancel={() => {
          setApproveDialog({ open: false, request: null });
          approveForm.resetFields();
        }}
        onOk={() => approveForm.submit()}
        confirmLoading={approve.isPending}
        okText={t("deletionRequest.admin.approve")}
        okButtonProps={{ danger: true, type: "primary" }}
        cancelText={t("workflow.modal.cancel")}
        destroyOnClose
      >
        {approveDialog.request && (
          <>
            <Typography.Paragraph>
              {t("deletionRequest.admin.approveConfirm", {
                codeset: approveDialog.request.codeset_name,
              })}
            </Typography.Paragraph>
            <Typography.Paragraph type="secondary">
              {t("deletionRequest.admin.softDeleteHint")}
            </Typography.Paragraph>
            <Form
              form={approveForm}
              layout="vertical"
              initialValues={{
                force_archive: false,
                decision_comment: "",
              }}
              onFinish={(v) =>
                approve.mutate({
                  id: approveDialog.request!.id,
                  decisionComment: v.decision_comment,
                  forceArchive: !!v.force_archive,
                })
              }
            >
              {approveDialog.request.has_published_versions && (
                <Form.Item
                  name="force_archive"
                  valuePropName="checked"
                  extra={t("deletionRequest.admin.forceArchiveHint")}
                >
                  <Checkbox>{t("deletionRequest.admin.forceArchive")}</Checkbox>
                </Form.Item>
              )}
              <Form.Item
                name="decision_comment"
                label={t("deletionRequest.admin.decisionCommentOptional")}
              >
                <Input.TextArea rows={3} maxLength={2000} showCount />
              </Form.Item>
            </Form>
          </>
        )}
      </Modal>

      <Modal
        title={t("deletionRequest.admin.rejectTitle")}
        open={rejectDialog.open}
        onCancel={() => {
          setRejectDialog({ open: false, request: null });
          rejectForm.resetFields();
        }}
        onOk={() => rejectForm.submit()}
        confirmLoading={reject.isPending}
        okText={t("deletionRequest.admin.reject")}
        cancelText={t("workflow.modal.cancel")}
        destroyOnClose
      >
        {rejectDialog.request && (
          <Form
            form={rejectForm}
            layout="vertical"
            onFinish={(v) =>
              reject.mutate({
                id: rejectDialog.request!.id,
                comment: v.decision_comment.trim(),
              })
            }
          >
            <Form.Item
              name="decision_comment"
              label={t("deletionRequest.admin.decisionCommentRequired")}
              rules={[
                {
                  required: true,
                  min: 10,
                  max: 2000,
                  message: t("deletionRequest.admin.decisionCommentRule", {
                    min: 10,
                    max: 2000,
                  }),
                },
              ]}
            >
              <Input.TextArea rows={5} maxLength={2000} showCount />
            </Form.Item>
          </Form>
        )}
      </Modal>
    </>
  );
}
