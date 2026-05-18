import { Button, Popconfirm, App as AntApp } from "antd";
import { DeleteOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  versionId: string;
  codesetId: string;
}

export function DeleteDraftButton({ versionId, codesetId }: Props) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();

  const remove = useMutation({
    mutationFn: () => apiMutations.deleteDraft(versionId),
    onSuccess: () => {
      message.success(t("version.deleteSuccess"));
      queryClient.invalidateQueries({ queryKey: qk.versions.byCodeset(codesetId) });
      queryClient.invalidateQueries({ queryKey: qk.codesets.one(codesetId) });
      // версии больше нет — навигируем обратно на CodeSet.
      navigate(`/codesets/${codesetId}`, { replace: true });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  return (
    <Popconfirm
      title={t("version.deleteConfirmTitle")}
      description={t("version.deleteConfirmDescription")}
      okText={t("version.deleteConfirmOk")}
      okButtonProps={{ danger: true }}
      cancelText={t("workflow.modal.cancel")}
      onConfirm={() => remove.mutate()}
    >
      <Button danger icon={<DeleteOutlined />} loading={remove.isPending}>
        {t("version.deleteDraft")}
      </Button>
    </Popconfirm>
  );
}
