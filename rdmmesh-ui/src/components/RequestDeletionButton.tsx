import { useState } from "react";
import { App as AntApp, Button, Form, Input, Modal, Space, Tag, Typography } from "antd";
import { DeleteOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { adminMutations, deletionRequestsApi } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  codesetId: string;
}

const REASON_MIN = 10;
const REASON_MAX = 2000;

/**
 * E22: Author просит Admin'а удалить CodeSet с обоснованием.
 *
 * <p>Кнопка имеет два визуальных состояния:
 * <ol>
 *   <li>Нет своей PENDING-заявки → primary danger «Запросить удаление».
 *       Если PENDING подал другой Author — backend вернёт 409, UI покажет toast.</li>
 *   <li>Своя PENDING-заявка → тег "PENDING" + кнопка Cancel.
 *       Список /my возвращает только запросы текущего пользователя.</li>
 * </ol>
 */
export function RequestDeletionButton({ codesetId }: Props) {
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ reason: string }>();

  const myRequests = useQuery({
    queryKey: qk.deletionRequests.my(),
    queryFn: () => deletionRequestsApi.listMy(),
  });
  const myPendingForThisCodeset = myRequests.data?.find(
    (r) => r.codeset_id === codesetId && r.status === "PENDING",
  );

  const submit = useMutation({
    mutationFn: (reason: string) =>
      adminMutations.submitDeletionRequest(codesetId, reason),
    onSuccess: () => {
      message.success(t("deletionRequest.submitSuccess"));
      setOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: qk.deletionRequests.my() });
      queryClient.invalidateQueries({ queryKey: qk.admin.deletionRequests("PENDING") });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const cancel = useMutation({
    mutationFn: (id: string) => adminMutations.cancelDeletionRequest(id),
    onSuccess: () => {
      message.success(t("deletionRequest.cancelSuccess"));
      queryClient.invalidateQueries({ queryKey: qk.deletionRequests.my() });
      queryClient.invalidateQueries({ queryKey: qk.admin.deletionRequests("PENDING") });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  if (myPendingForThisCodeset) {
    return (
      <Space>
        <Tag color="orange">{t("deletionRequest.pendingTag")}</Tag>
        <Button
          size="small"
          danger
          loading={cancel.isPending}
          onClick={() => cancel.mutate(myPendingForThisCodeset.id)}
        >
          {t("deletionRequest.cancelButton")}
        </Button>
      </Space>
    );
  }

  return (
    <>
      <Button danger icon={<DeleteOutlined />} onClick={() => setOpen(true)}>
        {t("deletionRequest.requestButton")}
      </Button>
      <Modal
        open={open}
        title={t("deletionRequest.modalTitle")}
        okText={t("deletionRequest.submitButton")}
        okButtonProps={{ danger: true }}
        cancelText={t("workflow.modal.cancel")}
        confirmLoading={submit.isPending}
        onOk={() => form.submit()}
        onCancel={() => {
          setOpen(false);
          form.resetFields();
        }}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary">
          {t("deletionRequest.modalDescription")}
        </Typography.Paragraph>
        <Form
          form={form}
          layout="vertical"
          onFinish={(v) => submit.mutate(v.reason.trim())}
        >
          <Form.Item
            name="reason"
            label={t("deletionRequest.reasonLabel")}
            rules={[
              { required: true, message: t("deletionRequest.reasonRequired") },
              {
                min: REASON_MIN,
                max: REASON_MAX,
                message: t("deletionRequest.reasonLength", {
                  min: REASON_MIN,
                  max: REASON_MAX,
                }),
              },
            ]}
          >
            <Input.TextArea
              rows={6}
              maxLength={REASON_MAX}
              showCount
              placeholder={t("deletionRequest.reasonPlaceholder")}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
