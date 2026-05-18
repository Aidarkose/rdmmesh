import { useMemo, useState } from "react";
import {
  Alert,
  Button,
  Col,
  Drawer,
  Empty,
  Row,
  Select,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from "antd";
import { DiffOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";

import { api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";
import type { CodeSetVersion, DiffEntry, DiffOp, VersionDiffResponse } from "@/api/types";

interface Props {
  toVersion: CodeSetVersion;
  versionsInCodeset: CodeSetVersion[];
}

const OP_COLOR: Record<DiffOp, string> = {
  ADDED: "green",
  CHANGED: "blue",
  REMOVED: "red",
  MOVED: "purple",
};

export function DiffButton({ toVersion, versionsInCodeset }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  const candidates = useMemo(
    () =>
      versionsInCodeset
        .filter((v) => v.id !== toVersion.id)
        // Сортировка по published_at DESC, а потом по created_at DESC — самые свежие сверху.
        .slice()
        .sort((a, b) => {
          const ap = a.published_at ?? a.created_at ?? "";
          const bp = b.published_at ?? b.created_at ?? "";
          return bp.localeCompare(ap);
        }),
    [versionsInCodeset, toVersion.id],
  );

  return (
    <>
      <Button icon={<DiffOutlined />} onClick={() => setOpen(true)} disabled={candidates.length === 0}>
        {t("diff.button")}
      </Button>
      <Drawer
        title={t("diff.title", { version: toVersion.version })}
        open={open}
        onClose={() => setOpen(false)}
        width="80%"
        destroyOnClose
      >
        <DiffPanel toVersion={toVersion} candidates={candidates} />
      </Drawer>
    </>
  );
}

function DiffPanel({
  toVersion,
  candidates,
}: {
  toVersion: CodeSetVersion;
  candidates: CodeSetVersion[];
}) {
  const { t } = useTranslation();
  const [fromId, setFromId] = useState<string | null>(null);

  return (
    <Space direction="vertical" style={{ width: "100%" }} size="middle">
      <Row gutter={16} align="middle">
        <Col flex="auto">
          <Typography.Text type="secondary">{t("diff.compareFrom")}</Typography.Text>
        </Col>
        <Col flex="none">
          <Select
            style={{ minWidth: 280 }}
            placeholder={t("diff.pickVersion")}
            value={fromId}
            onChange={(v: string) => setFromId(v)}
            options={candidates.map((v) => ({
              value: v.id,
              label: `v${v.version} · ${v.status}`,
            }))}
          />
        </Col>
      </Row>

      {fromId == null ? (
        <Empty description={t("diff.pickPrompt")} />
      ) : (
        <DiffResult toVersionId={toVersion.id} fromVersionId={fromId} />
      )}
    </Space>
  );
}

function DiffResult({ toVersionId, fromVersionId }: { toVersionId: string; fromVersionId: string }) {
  const { t } = useTranslation();

  const q = useQuery<VersionDiffResponse>({
    queryKey: qk.versions.diff(toVersionId, fromVersionId),
    queryFn: () => api.diffVersions(toVersionId, fromVersionId),
  });

  if (q.isLoading) return <Spin />;
  if (q.error) {
    const msg = q.error instanceof ApiError ? `${q.error.status}: ${q.error.message}` : String(q.error);
    return <Alert type="error" message={msg} showIcon />;
  }
  const data = q.data;
  if (!data) return null;

  const summary = data.summary;
  const entries = data.entries;

  const columns: TableColumnsType<DiffEntry> = [
    {
      title: "op",
      dataIndex: "op",
      key: "op",
      width: 110,
      render: (op: DiffOp) => <Tag color={OP_COLOR[op] ?? "default"}>{op}</Tag>,
      filters: (["ADDED", "CHANGED", "REMOVED", "MOVED"] as DiffOp[]).map((op) => ({
        text: op,
        value: op,
      })),
      onFilter: (value, row) => row.op === value,
    },
    {
      title: t("items.key"),
      dataIndex: "key_parts",
      key: "key_parts",
      render: (v: string[]) => <code>{v.join(" / ")}</code>,
      width: 220,
    },
    {
      title: t("diff.changedFields"),
      dataIndex: "changed_fields",
      key: "changed_fields",
      width: 240,
      render: (v: string[] | null | undefined) =>
        v && v.length > 0 ? (
          <Space size={[4, 4]} wrap>
            {v.map((f) => (
              <Tag key={f}>{f}</Tag>
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
  ];

  return (
    <Space direction="vertical" style={{ width: "100%" }} size="middle">
      <Space wrap size="large">
        <Statistic title="from" value={data.from_version} />
        <Statistic title="to" value={data.to_version} />
        <Statistic title={<Tag color="green">ADDED</Tag>} value={summary.added} />
        <Statistic title={<Tag color="blue">CHANGED</Tag>} value={summary.changed} />
        <Statistic title={<Tag color="red">REMOVED</Tag>} value={summary.removed} />
        <Statistic title={<Tag color="purple">MOVED</Tag>} value={summary.moved} />
      </Space>
      <Table<DiffEntry>
        rowKey={(r, i) => `${r.op}-${(r.key_parts ?? []).join("/")}-${i}`}
        dataSource={entries}
        columns={columns}
        size="small"
        pagination={{ pageSize: 25, showSizeChanger: true, pageSizeOptions: [10, 25, 50, 100] }}
        expandable={{
          expandedRowRender: (row) => <BeforeAfter row={row} />,
          rowExpandable: (row) => row.before != null || row.after != null,
        }}
      />
    </Space>
  );
}

function BeforeAfter({ row }: { row: DiffEntry }) {
  const { t } = useTranslation();
  return (
    <Row gutter={16}>
      <Col span={12}>
        <Typography.Text strong>{t("diff.before")}</Typography.Text>
        <pre style={paneStyle}>{row.before == null ? "—" : JSON.stringify(row.before, null, 2)}</pre>
      </Col>
      <Col span={12}>
        <Typography.Text strong>{t("diff.after")}</Typography.Text>
        <pre style={paneStyle}>{row.after == null ? "—" : JSON.stringify(row.after, null, 2)}</pre>
      </Col>
    </Row>
  );
}

const paneStyle: React.CSSProperties = {
  background: "#fafafa",
  padding: 12,
  borderRadius: 4,
  fontSize: 12,
  maxHeight: 320,
  overflow: "auto",
  margin: 0,
};
