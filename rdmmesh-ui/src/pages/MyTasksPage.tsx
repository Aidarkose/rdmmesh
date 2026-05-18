import { Card, List, Tag, Typography } from "antd";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { Loader } from "@/components/Loader";

export function MyTasksPage() {
  const { t } = useTranslation();
  const state = useApi(api.myTasks, qk.tasks.my());

  return (
    <Card title={t("tasks.title")}>
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
                        <Tag color="blue">{task.requiredRole}</Tag>
                        <Typography.Text strong>
                          {t("tasks.codeset")}: {task.codesetId}
                        </Typography.Text>
                      </>
                    }
                    description={
                      <>
                        <Typography.Text type="secondary">
                          {t("tasks.version")}: {task.versionId}
                        </Typography.Text>
                        <br />
                        <Typography.Text type="secondary">
                          {t("tasks.createdAt")}: {task.createdAt}
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
  );
}
