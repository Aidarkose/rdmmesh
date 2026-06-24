import { Card, List, Typography } from "antd";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { Loader } from "@/components/Loader";

export function CatalogPage() {
  const { t } = useTranslation();
  const state = useApi(api.listDomains, qk.domains.all());

  return (
    <Card title={t("catalog.title")}>
      <Loader {...state}>
        {(domains) => (
          <List
            dataSource={domains}
            locale={{ emptyText: t("common.empty") }}
            renderItem={(d) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Link to={`/domains/${d.id}`}>
                      <Typography.Text strong>{d.display_name ?? d.name}</Typography.Text>
                      <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                        ({d.name})
                      </Typography.Text>
                    </Link>
                  }
                  description={d.description ?? null}
                />
              </List.Item>
            )}
          />
        )}
      </Loader>
    </Card>
  );
}
