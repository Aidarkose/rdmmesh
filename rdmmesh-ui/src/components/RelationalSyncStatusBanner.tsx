import { Alert, App as AntApp, Button, Typography } from "antd";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, apiMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useAuth } from "@/auth/AuthContext";
import { ApiError } from "@/api/client";

interface Props {
  codesetId: string;
  versionId: string;
}

/**
 * Stage 7 (A): видимый статус синхронизации rd_data после публикации версии.
 * Молчит при OK / UNKNOWN; при STALE (пост-коммитная пересборка упала) и BLOCKED
 * (публикация отклонена пред-проверкой) показывает причину. Для BLOCKED/STALE
 * под RDM_ADMIN даёт кнопку «Повторить публикацию» (POST .../publish-retry).
 */
export function RelationalSyncStatusBanner({ codesetId, versionId }: Props) {
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();
  const { baseRoles } = useAuth();
  const isAdmin = baseRoles.includes("RDM_ADMIN");

  const status = useQuery({
    queryKey: qk.versions.syncStatus(versionId),
    queryFn: () => api.relationalSyncStatus(codesetId, versionId),
    staleTime: 5_000,
  });

  const retry = useMutation({
    mutationFn: () => apiMutations.retryPublish(versionId),
    onSuccess: (r) => {
      if (r.outcome === "PUBLISHED") message.success("Опубликовано — rd_data синхронизирован");
      else if (r.outcome === "BLOCKED") message.warning("Снова заблокировано — причина не устранена");
      else message.info(`Результат: ${r.outcome}`);
      queryClient.invalidateQueries({ queryKey: qk.versions.syncStatus(versionId) });
      queryClient.invalidateQueries({ queryKey: qk.versions.one(versionId) });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const state = status.data?.state;
  if (!state || state === "OK" || state === "UNKNOWN") return null;

  const blocked = state === "BLOCKED";
  return (
    <Alert
      type={blocked ? "error" : "warning"}
      showIcon
      style={{ marginTop: 16 }}
      message={
        blocked
          ? "Публикация отклонена: не удаётся синхронизировать rd_data"
          : "rd_data не синхронизирован с этой версией"
      }
      description={
        <>
          <Typography.Paragraph style={{ marginBottom: 8 }}>
            {blocked
              ? "Пред-проверка показала, что пересобрать физические таблицы под эту версию нельзя (обычно — конфликт материализованного внешнего ключа). Версия не опубликована физически."
              : "Версия отмечена опубликованной, но пересборка физических таблиц после публикации не удалась — данные в rd_data отстают."}
          </Typography.Paragraph>
          {status.data?.reason && (
            <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
              Причина: <Typography.Text code>{status.data.reason}</Typography.Text>
            </Typography.Paragraph>
          )}
          {isAdmin && (
            <Button danger={blocked} size="small" loading={retry.isPending} onClick={() => retry.mutate()}>
              Повторить публикацию
            </Button>
          )}
        </>
      }
    />
  );
}
