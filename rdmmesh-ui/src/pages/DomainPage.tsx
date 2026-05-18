import { Breadcrumb, Card, List, Tag, Typography } from "antd";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { Loader } from "@/components/Loader";

export function DomainPage() {
  const { t } = useTranslation();
  const { domainId } = useParams<{ domainId: string }>();
  const id = domainId!;

  const domain = useApi(() => api.getDomain(id), qk.domains.one(id));
  const codesets = useApi(() => api.listCodeSetsByDomain(id), qk.codesets.byDomain(id));

  return (
    <>
      <Breadcrumb
        style={{ marginBottom: 16 }}
        items={[
          { title: <Link to="/catalog">{t("nav.catalog")}</Link> },
          { title: domain.data?.display_name ?? domain.data?.name ?? "..." },
        ]}
      />
      <Card title={t("catalog.domain")} style={{ marginBottom: 16 }}>
        <Loader {...domain}>
          {(d) => (
            <>
              <Typography.Title level={4} style={{ marginTop: 0 }}>
                {d.display_name ?? d.name}
              </Typography.Title>
              <Typography.Text type="secondary">{d.name}</Typography.Text>
              {d.description && (
                <Typography.Paragraph style={{ marginTop: 12 }}>
                  {d.description}
                </Typography.Paragraph>
              )}
              {d.tags && d.tags.length > 0 && (
                <div style={{ marginTop: 12 }}>
                  {d.tags.map((tag) => (
                    <Tag key={tag}>{tag}</Tag>
                  ))}
                </div>
              )}
            </>
          )}
        </Loader>
      </Card>

      <Card title={t("catalog.codesets")}>
        <Loader {...codesets}>
          {(items) => (
            <List
              dataSource={items}
              locale={{ emptyText: t("common.empty") }}
              renderItem={(c) => (
                <List.Item>
                  <List.Item.Meta
                    title={
                      <Link to={`/codesets/${c.id}`}>
                        <Typography.Text strong>{c.display_name ?? c.name}</Typography.Text>
                      </Link>
                    }
                    description={
                      <>
                        <Typography.Text type="secondary">{c.name}</Typography.Text>
                        {c.current_published_version ? (
                          <Tag color="green" style={{ marginLeft: 8 }}>
                            v{c.current_published_version}
                          </Tag>
                        ) : (
                          <Tag style={{ marginLeft: 8 }}>{t("catalog.noPublished")}</Tag>
                        )}
                        <Tag style={{ marginLeft: 8 }}>{c.hierarchy_mode}</Tag>
                      </>
                    }
                  />
                </List.Item>
              )}
            />
          )}
        </Loader>
      </Card>
    </>
  );
}
