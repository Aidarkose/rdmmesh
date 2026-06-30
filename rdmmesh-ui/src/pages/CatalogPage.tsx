import { type ReactNode } from "react";
import { Card, Empty, Tree, Typography } from "antd";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { Loader } from "@/components/Loader";
import type { Domain } from "@/api/types";

interface DomainNode {
  key: string;
  title: ReactNode;
  children?: DomainNode[];
}

function renderTitle(d: Domain): ReactNode {
  return (
    <Link to={`/domains/${d.id}`}>
      <Typography.Text strong>{d.display_name ?? d.name}</Typography.Text>
      <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
        ({d.name})
      </Typography.Text>
    </Link>
  );
}

/**
 * Строим лес доменов по parent_om_domain_id → om_domain_id (OM — мастер иерархии,
 * Phase 2). Сначала создаём все узлы (родитель может прийти после потомка), затем
 * линкуем. Домен без parent либо со ссылкой на отсутствующий om_domain_id — корень.
 */
function buildDomainForest(domains: Domain[]): DomainNode[] {
  const nodeByOmId = new Map<string, DomainNode>();
  const entries = domains.map((d) => {
    const node: DomainNode = { key: d.id, title: renderTitle(d) };
    if (d.om_domain_id) nodeByOmId.set(d.om_domain_id, node);
    return { d, node };
  });
  const roots: DomainNode[] = [];
  for (const { d, node } of entries) {
    const parent = d.parent_om_domain_id ? nodeByOmId.get(d.parent_om_domain_id) : undefined;
    if (parent) {
      (parent.children ??= []).push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

export function CatalogPage() {
  const { t } = useTranslation();
  const state = useApi(api.listDomains, qk.domains.all());

  return (
    <Card title={t("catalog.title")}>
      <Loader {...state}>
        {(domains) =>
          domains.length === 0 ? (
            <Empty description={t("common.empty")} />
          ) : (
            <Tree
              treeData={buildDomainForest(domains)}
              showLine
              defaultExpandAll
              selectable={false}
              blockNode
            />
          )
        }
      </Loader>
    </Card>
  );
}
