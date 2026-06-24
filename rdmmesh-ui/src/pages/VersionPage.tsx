import { useCallback, useState } from "react";
import {
  Alert,
  Breadcrumb,
  Button,
  Card,
  Descriptions,
  Pagination,
  Segmented,
  Space,
  Tag,
  Timeline,
  Typography,
} from "antd";
import {
  CheckCircleTwoTone,
  CloseCircleTwoTone,
  SafetyCertificateOutlined,
} from "@ant-design/icons";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import { useQueryClient } from "@tanstack/react-query";
import { Loader } from "@/components/Loader";
import { StatusTag } from "@/components/StatusTag";
import { ItemsTable } from "@/components/ItemsTable";
import { WorkflowActions } from "@/components/WorkflowActions";
import { AddItemButton } from "@/components/AddItemModal";
import { DeleteDraftButton } from "@/components/DeleteDraftButton";
import { BulkImportButton } from "@/components/BulkImportModal";
import { ClearAllItemsButton } from "@/components/ClearAllItemsButton";
import { DiffButton } from "@/components/DiffViewer";
import { ConsumerViewButton } from "@/components/ConsumerViewDrawer";
import { HierarchyTree } from "@/components/HierarchyTree";
import { RebuildClosureButton } from "@/components/RebuildClosureButton";
import { RatingTransitionPivotView } from "@/components/RatingTransitionPivotView";
import { RelationalSyncStatusBanner } from "@/components/RelationalSyncStatusBanner";
import type { CodeSet, CodeSetVersion, Domain, VerifyResponse } from "@/api/types";

type ViewMode = "grid" | "tree" | "pivot";

// E19: показываем toggle «Матрица» для CodeSet'ов с composite key (≥ 3 key_parts),
// что соответствует структуре [from, to, horizon]. Для одноключевых и двуключевых
// pivot-вью не имеет смысла — оставляем только Grid/Tree.
function hasMatrixKeySpec(codeset: CodeSet | null | undefined): boolean {
  return (codeset?.key_spec?.parts?.length ?? 0) >= 3;
}

export function VersionPage() {
  const { t } = useTranslation();
  const { versionId } = useParams<{ versionId: string }>();
  const vid = versionId!;

  const queryClient = useQueryClient();
  const version = useApi(() => api.getVersion(vid), qk.versions.one(vid));
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(100);
  const [viewMode, setViewMode] = useState<ViewMode>("grid");
  const handleViewModeChange = useCallback(
    (v: ViewMode) => {
      setViewMode(v);
      queryClient.refetchQueries({ queryKey: qk.versions.itemsRoot(vid) });
    },
    [vid, queryClient],
  );
  const itemsPage = useApi(() => api.listItems(vid, page, size), qk.versions.items(vid, page, size));
  const history = useApi(() => api.transitionHistory(vid), qk.versions.history(vid));
  // Список версий codeset'а нужен для DiffViewer (выбор from-версии). Загружаем
  // как только знаем codeset_id (ленивая зависимость через enabled-в-key).
  const codesetId = version.data?.codeset_id ?? null;
  const codesetVersions = useApi<CodeSetVersion[]>(
    () => (codesetId ? api.listVersionsByCodeSet(codesetId) : Promise.resolve([])),
    qk.versions.byCodeset(codesetId ?? "_pending"),
  );
  // E13: для ConsumerView Drawer нужны qualified_name domain'а и codeset'а
  // (distribution endpoint работает по именам, не UUID).
  const codeset = useApi<CodeSet | null>(
    () => (codesetId ? api.getCodeSet(codesetId) : Promise.resolve(null)),
    qk.codesets.one(codesetId ?? "_pending"),
  );
  const domainId = codeset.data?.domain_id ?? null;
  const domain = useApi<Domain | null>(
    () => (domainId ? api.getDomain(domainId) : Promise.resolve(null)),
    qk.domains.one(domainId ?? "_pending"),
  );
  const [verify, setVerify] = useState<{ loading: boolean; data: VerifyResponse | null; error: string | null }>(
    { loading: false, data: null, error: null },
  );

  const runVerify = async () => {
    setVerify({ loading: true, data: null, error: null });
    try {
      const data = await api.verifyVersion(vid);
      setVerify({ loading: false, data, error: null });
    } catch (e: unknown) {
      setVerify({
        loading: false,
        data: null,
        error: e instanceof Error ? e.message : String(e),
      });
    }
  };

  return (
    <>
      <Breadcrumb
        style={{ marginBottom: 16 }}
        items={[
          { title: <Link to="/catalog">{t("nav.catalog")}</Link> },
          {
            title: version.data ? (
              <Link to={`/codesets/${version.data.codeset_id}`}>{t("catalog.codeset")}</Link>
            ) : (
              "..."
            ),
          },
          { title: version.data ? `v${version.data.version}` : "..." },
        ]}
      />

      <Card title={t("version.title")} style={{ marginBottom: 16 }}>
        <Loader {...version}>
          {(v) => (
            <>
              <Space style={{ marginBottom: 12 }} wrap>
                <Typography.Title level={3} style={{ margin: 0 }}>
                  v{v.version}
                </Typography.Title>
                <StatusTag status={v.status} />
                {v.owner_was_provisional && <Tag color="orange">provisional owner</Tag>}
              </Space>
              <Descriptions column={1} size="small">
                <Descriptions.Item label={t("version.createdAt")}>{v.created_at ?? "—"}</Descriptions.Item>
                <Descriptions.Item label={t("version.createdBy")}>{v.created_by ?? "—"}</Descriptions.Item>
                <Descriptions.Item label={t("version.publishedAt")}>{v.published_at ?? "—"}</Descriptions.Item>
                <Descriptions.Item label={t("version.publishedBy")}>{v.published_by ?? "—"}</Descriptions.Item>
                <Descriptions.Item label={t("version.deprecatedAt")}>{v.deprecated_at ?? "—"}</Descriptions.Item>
                <Descriptions.Item label={t("version.itemCount")}>{v.item_count ?? 0}</Descriptions.Item>
                <Descriptions.Item label={t("version.contentHash")}>
                  {v.content_hash ? <code>{v.content_hash}</code> : "—"}
                </Descriptions.Item>
                <Descriptions.Item label={t("version.approvalSignature")}>
                  {v.approval_signature ? <code>{v.approval_signature}</code> : "—"}
                </Descriptions.Item>
              </Descriptions>

              <div style={{ marginTop: 16 }}>
                <Space wrap>
                  <WorkflowActions version={v} domainId={domainId} />
                  {v.status === "DRAFT" && <DeleteDraftButton versionId={v.id} codesetId={v.codeset_id} />}
                  {codesetVersions.data && (
                    <DiffButton toVersion={v} versionsInCodeset={codesetVersions.data} />
                  )}
                  {(v.status === "PUBLISHED" || v.status === "DEPRECATED") &&
                    domain.data &&
                    codeset.data && (
                      <ConsumerViewButton
                        domainName={domain.data.name}
                        codesetName={codeset.data.name}
                        currentVersion={v.version}
                      />
                    )}
                  <RebuildClosureButton versionId={v.id} />
                </Space>
              </div>

              {(v.status === "PUBLISHED" || v.status === "DEPRECATED") && (
                <div style={{ marginTop: 16 }}>
                  <Button
                    icon={<SafetyCertificateOutlined />}
                    onClick={() => void runVerify()}
                    loading={verify.loading}
                  >
                    {t("common.verify")}
                  </Button>
                  {verify.error && (
                    <Alert
                      type="error"
                      message={verify.error}
                      style={{ marginTop: 12 }}
                      showIcon
                    />
                  )}
                  {verify.data && <VerifyResult data={verify.data} />}
                </div>
              )}

              <RelationalSyncStatusBanner codesetId={v.codeset_id} versionId={vid} />
            </>
          )}
        </Loader>
      </Card>

      <Card
        title={t("items.title")}
        style={{ marginBottom: 16 }}
        extra={
          version.data?.status === "DRAFT" && (
            <Space>
              <BulkImportButton versionId={vid} codesetId={version.data.codeset_id} />
              <AddItemButton versionId={vid} codesetId={version.data.codeset_id} />
              {(version.data.item_count ?? 0) > 0 && (
                <ClearAllItemsButton versionId={vid} />
              )}
            </Space>
          )
        }
      >
        <Loader {...itemsPage}>
          {(p) => {
            const matrixMode = hasMatrixKeySpec(codeset.data);
            const treeMode = codeset.data && codeset.data.hierarchy_mode !== "NONE";
            const segOptions: Array<{ label: string; value: ViewMode }> = [
              { label: t("items.viewGrid"), value: "grid" },
            ];
            if (treeMode) segOptions.push({ label: t("items.viewTree"), value: "tree" });
            if (matrixMode) segOptions.push({ label: t("items.viewPivot"), value: "pivot" });
            // Если в коде уже выбран pivot/tree, а codeset больше его не поддерживает —
            // откатываемся на grid (защита от консистентности при смене codeset.data).
            const effectiveMode: ViewMode =
              (viewMode === "tree" && !treeMode) || (viewMode === "pivot" && !matrixMode)
                ? "grid"
                : viewMode;
            return (
              <>
                {(treeMode || matrixMode) && (
                  <div style={{ marginBottom: 12 }}>
                    <Segmented<ViewMode>
                      value={effectiveMode}
                      onChange={(v) => handleViewModeChange(v as ViewMode)}
                      options={segOptions}
                    />
                  </div>
                )}
                {effectiveMode === "tree" && version.data ? (
                  <HierarchyTree
                    items={p.items}
                    versionId={vid}
                    codesetId={version.data.codeset_id}
                    editable={version.data.status === "DRAFT"}
                  />
                ) : effectiveMode === "pivot" ? (
                  <RatingTransitionPivotView
                    items={p.items}
                    keySpec={codeset.data?.key_spec ?? null}
                  />
                ) : (
                  <>
                    <ItemsTable
                      items={p.items}
                      editable={version.data?.status === "DRAFT"}
                      versionId={vid}
                      codesetId={version.data?.codeset_id}
                    />
                    <div style={{ marginTop: 12, display: "flex", justifyContent: "flex-end" }}>
                      <Pagination
                        current={p.page + 1}
                        pageSize={p.size}
                        total={p.total}
                        showSizeChanger
                        pageSizeOptions={[50, 100, 250, 500, 1000]}
                        onChange={(nextPage, nextSize) => {
                          setPage(nextPage - 1);
                          setSize(nextSize);
                        }}
                      />
                    </div>
                  </>
                )}
              </>
            );
          }}
        </Loader>
      </Card>

      <Card title={t("history.title")}>
        <Loader {...history}>
          {(events) =>
            events.length === 0 ? (
              <Typography.Text type="secondary">{t("common.empty")}</Typography.Text>
            ) : (
              <Timeline
                items={events.map((e) => ({
                  color: e.to_status === "PUBLISHED" ? "green" : "blue",
                  children: (
                    <>
                      <Typography.Text strong>{e.action}</Typography.Text>
                      <span style={{ marginLeft: 8 }}>
                        {e.from_status ? <Tag>{e.from_status}</Tag> : null}
                        →
                        <Tag style={{ marginLeft: 4 }}>{e.to_status}</Tag>
                      </span>
                      <div style={{ fontSize: 12, color: "#888" }}>
                        {e.occurred_at} · {e.actor}
                      </div>
                      {e.comment && (
                        <Typography.Paragraph style={{ marginBottom: 0, marginTop: 4 }}>
                          {e.comment}
                        </Typography.Paragraph>
                      )}
                    </>
                  ),
                }))}
              />
            )
          }
        </Loader>
      </Card>
    </>
  );
}

function VerifyResult({ data }: { data: VerifyResponse }) {
  const { t } = useTranslation();
  const ok = data.applicable && data.verified;
  return (
    <Card type="inner" size="small" style={{ marginTop: 12 }}>
      <Space direction="vertical" size={4} style={{ width: "100%" }}>
        <div>
          {ok ? (
            <CheckCircleTwoTone twoToneColor="#52c41a" />
          ) : (
            <CloseCircleTwoTone twoToneColor="#ff4d4f" />
          )}
          <Typography.Text strong style={{ marginLeft: 8 }}>
            {data.applicable ? t("version.verify.verified") : t("version.verify.applicable")}:{" "}
            {String(ok)}
          </Typography.Text>
        </div>
        {data.computedHash && (
          <Typography.Text type="secondary">
            {t("version.verify.computed")}: <code>{data.computedHash}</code>
          </Typography.Text>
        )}
        {data.storedHash && (
          <Typography.Text type="secondary">
            {t("version.verify.stored")}: <code>{data.storedHash}</code>
          </Typography.Text>
        )}
        {data.note && (
          <Typography.Text type="secondary">
            {t("version.verify.note")}: {data.note}
          </Typography.Text>
        )}
      </Space>
    </Card>
  );
}
