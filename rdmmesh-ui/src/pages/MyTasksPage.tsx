import { Card, List, Tag, Typography } from "antd";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";

import { adminApi, api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { useAuth } from "@/auth/AuthContext";
import { Loader } from "@/components/Loader";

// Имя справочника по его id (в задаче приходит только codesetId-UUID). Пока грузится —
// показываем «код справочника <короткий id>». Ссылка ведёт на страницу справочника.
function CodesetLabel({ codesetId }: { codesetId: string }) {
  const { data } = useQuery({
    queryKey: qk.codesets.one(codesetId),
    queryFn: () => api.getCodeSet(codesetId),
  });
  const label = data ? (data.display_name ?? data.name) : `код справочника ${codesetId.slice(0, 8)}…`;
  return <Link to={`/codesets/${codesetId}`}>{label}</Link>;
}

// createdAt приходит эпохой в секундах (Instant → timestamp), напр. 1782802921.477305.
// Форматируем в локальную дату/время; на всякий случай понимаем и ISO-строку.
function formatTs(v: string | number | null | undefined): string {
  if (v == null || v === "") return "—";
  const n = typeof v === "number" ? v : Number(v);
  const d = Number.isFinite(n) ? new Date(n * 1000) : new Date(String(v));
  return isNaN(d.getTime()) ? String(v) : d.toLocaleString();
}

export function MyTasksPage() {
  const { t } = useTranslation();
  const { baseRoles } = useAuth();
  const isAdmin = baseRoles.includes("RDM_ADMIN");

  const state = useApi(api.myTasks, qk.tasks.my());

  // E18.6 — admin-задачи (PENDING resolution_task). Запрос идёт только если
  // у пользователя есть RDM_ADMIN: enabled=false полностью гасит fetch.
  const adminTasks = useQuery({
    queryKey: qk.admin.tasksMy(),
    queryFn: adminApi.myAdminTasks,
    enabled: isAdmin,
  });

  return (
    <>
      <Card title={t("tasks.title")} style={{ marginBottom: 16 }}>
        <Loader {...state}>
          {(tasks) =>
            tasks.length === 0 ? (
              <Typography.Text type="secondary">{t("tasks.empty")}</Typography.Text>
            ) : (
              <List
                dataSource={tasks}
                renderItem={(task) => (
                  <List.Item
                    actions={[
                      <Link key="open" to={`/versions/${task.versionId}`}>
                        {t("tasks.open")}
                      </Link>,
                    ]}
                  >
                    <List.Item.Meta
                      title={
                        <>
                          <Tag color="blue">
                            {task.assignedRole
                              ? t(`tasks.role.${task.assignedRole}`)
                              : task.requiredRole}
                          </Tag>
                          <Typography.Text strong>
                            {t("tasks.codeset")}: <CodesetLabel codesetId={task.codesetId} />
                          </Typography.Text>
                        </>
                      }
                      description={
                        <>
                          <Typography.Text type="secondary">
                            {t("tasks.version")}: {task.versionId.slice(0, 8)}…
                          </Typography.Text>
                          <br />
                          <Typography.Text type="secondary">
                            {t("tasks.createdAt")}: {formatTs(task.createdAt)}
                          </Typography.Text>
                        </>
                      }
                    />
                  </List.Item>
                )}
              />
            )
          }
        </Loader>
      </Card>

      {isAdmin && (
        <Card
          title={
            <span>
              Задачи администратора <Tag color="gold">RDM_ADMIN</Tag>{" "}
              <Tag>{adminTasks.data?.length ?? 0}</Tag>
            </span>
          }
        >
          {adminTasks.isLoading ? (
            <Typography.Text type="secondary">Загрузка…</Typography.Text>
          ) : (adminTasks.data ?? []).length === 0 ? (
            <Typography.Text type="secondary">
              Нет нерешённых конфликтов. Задачи появятся, когда OM-webhook не сможет
              автоматически применить событие (например, коллизия по имени с
              RDM-локальным доменом — E18.3 webhook upgrade).
            </Typography.Text>
          ) : (
            <List
              dataSource={adminTasks.data ?? []}
              renderItem={(t) => (
                <List.Item>
                  <List.Item.Meta
                    title={
                      <>
                        <Tag color="orange">{t.task_type}</Tag>
                        <Typography.Text>{t.id.slice(0, 8)}…</Typography.Text>
                      </>
                    }
                    description={
                      <>
                        <Typography.Text type="secondary">
                          source_event: {t.source_event_id}
                        </Typography.Text>
                        <br />
                        <Typography.Text type="secondary">
                          {new Date(t.created_at).toLocaleString()}
                        </Typography.Text>
                      </>
                    }
                  />
                </List.Item>
              )}
            />
          )}
        </Card>
      )}
    </>
  );
}
