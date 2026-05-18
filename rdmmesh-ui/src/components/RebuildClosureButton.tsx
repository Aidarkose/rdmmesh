import { Button, Popconfirm, Tooltip, App as AntApp } from "antd";
import { ToolOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";
import { useAuth } from "@/auth/AuthContext";

interface Props {
  versionId: string;
}

/**
 * E13 round 3 — disaster-recovery: вызов POST /versions/{id}/closure/rebuild.
 *
 * Кнопка появляется только для пользователей с base-role RDM_ADMIN. Backend
 * gate'ит этот endpoint через @RolesAllowed; frontend-check — только UX.
 *
 * В нормальной работе closure обслуживается триггерами (V022/V023) — кнопка
 * нужна на случай инцидента или WARN'а из V023 sanity check при апгрейде.
 */
export function RebuildClosureButton({ versionId }: Props) {
  const { t } = useTranslation();
  const { baseRoles } = useAuth();
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();

  const rebuild = useMutation({
    mutationFn: () => apiMutations.rebuildClosure(versionId),
    onSuccess: (result) => {
      message.success(
        t("closure.rebuildSuccess", {
          removed: result.removed,
          inserted: result.inserted,
          total: result.total,
        }),
      );
      queryClient.invalidateQueries({ queryKey: qk.versions.itemsRoot(versionId) });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  if (!baseRoles.includes("RDM_ADMIN")) return null;

  return (
    <Popconfirm
      title={t("closure.rebuildConfirmTitle")}
      description={t("closure.rebuildConfirmDescription")}
      okText={t("closure.rebuildOk")}
      cancelText={t("workflow.modal.cancel")}
      onConfirm={() => rebuild.mutate()}
    >
      <Tooltip title={t("closure.rebuildTooltip")}>
        <Button icon={<ToolOutlined />} loading={rebuild.isPending}>
          {t("closure.rebuildButton")}
        </Button>
      </Tooltip>
    </Popconfirm>
  );
}
