import { useState } from "react";
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  App as AntApp,
  type TableColumnsType,
} from "antd";
import {
  DownloadOutlined,
  EyeOutlined,
  FileExcelOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import dayjs, { type Dayjs } from "dayjs";

import { api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";
import type { DistributionItem, Lang } from "@/api/types";

interface Props {
  domainName: string;
  codesetName: string;
  currentVersion?: string | null;
}

interface ResolvedFilters {
  version: string | null;
  asOf: string | null; // YYYY-MM-DD
  knowledgeAsOf: string | null; // ISO instant
  lang: Lang;
}

interface FormValues {
  version?: string;
  asOf?: Dayjs | null;
  knowledgeAsOf?: Dayjs | null;
  lang?: Lang;
}

const PAGE_SIZE = 1000;

export function ConsumerViewButton({ domainName, codesetName, currentVersion }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  return (
    <>
      <Button icon={<EyeOutlined />} onClick={() => setOpen(true)}>
        {t("consumer.button")}
      </Button>
      <Drawer
        title={t("consumer.title")}
        width="80%"
        open={open}
        onClose={() => setOpen(false)}
        destroyOnClose
      >
        <ConsumerViewPanel
          domainName={domainName}
          codesetName={codesetName}
          currentVersion={currentVersion}
        />
      </Drawer>
    </>
  );
}

function ConsumerViewPanel({ domainName, codesetName, currentVersion }: Props) {
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<FormValues>();
  const [exporting, setExporting] = useState<"xlsx" | "csv" | "json" | null>(null);
  // По умолчанию: «published», без bitemporal-фильтров, lang=ru.
  const [filters, setFilters] = useState<ResolvedFilters>({
    version: currentVersion ?? null,
    asOf: null,
    knowledgeAsOf: null,
    lang: "ru",
  });

  const query = useQuery({
    queryKey: qk.distribution.items(domainName, codesetName, {
      version: filters.version,
      asOf: filters.asOf,
      knowledgeAsOf: filters.knowledgeAsOf,
      lang: filters.lang,
      page: 1,
      size: PAGE_SIZE,
    }),
    queryFn: () =>
      api.distributionItems(domainName, codesetName, {
        version: filters.version,
        asOf: filters.asOf,
        knowledgeAsOf: filters.knowledgeAsOf,
        lang: filters.lang,
        page: 1,
        size: PAGE_SIZE,
      }),
  });

  const apply = (values: FormValues) => {
    setFilters({
      version: values.version?.trim() || null,
      asOf: values.asOf ? values.asOf.format("YYYY-MM-DD") : null,
      knowledgeAsOf: values.knowledgeAsOf ? values.knowledgeAsOf.toISOString() : null,
      lang: values.lang ?? "ru",
    });
  };

  const reset = () => {
    form.resetFields();
    setFilters({
      version: currentVersion ?? null,
      asOf: null,
      knowledgeAsOf: null,
      lang: "ru",
    });
  };

  const doExport = (format: "xlsx" | "csv" | "json") => {
    setExporting(format);
    api
      .downloadDistributionExport(domainName, codesetName, format, {
        version: filters.version,
        asOf: filters.asOf,
        knowledgeAsOf: filters.knowledgeAsOf,
        lang: filters.lang,
      })
      .catch((e: unknown) => {
        const msg =
          e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
        message.error(`${t("consumer.exportError")} — ${msg}`);
      })
      .finally(() => setExporting(null));
  };

  const attrKeys = (() => {
    if (!query.data) return [] as string[];
    const keys = new Set<string>();
    for (const it of query.data.items) {
      if (it.attributes) for (const k of Object.keys(it.attributes)) keys.add(k);
    }
    return Array.from(keys).sort();
  })();

  const columns: TableColumnsType<DistributionItem> = [
    {
      title: t("items.key"),
      key: "key",
      fixed: "left",
      width: 180,
      render: (_, row) => (
        <code>{Array.isArray(row.keyParts) ? row.keyParts.join(" / ") : String(row.keyParts)}</code>
      ),
    },
    {
      title: t("consumer.label"),
      dataIndex: "label",
      key: "label",
      width: 240,
      render: (v: string | null | undefined) => v ?? "",
    },
    {
      title: t("items.parentKey"),
      key: "parentKey",
      width: 140,
      render: (_, row) =>
        row.parentKey && row.parentKey.length > 0 ? (
          <code>{row.parentKey.length === 1 ? row.parentKey[0] : JSON.stringify(row.parentKey)}</code>
        ) : (
          ""
        ),
    },
    ...attrKeys.map<TableColumnsType<DistributionItem>[number]>((k) => ({
      title: k,
      key: `attr.${k}`,
      width: 140,
      render: (_, row) => formatAttr(row.attributes?.[k]),
    })),
    {
      title: t("items.status"),
      dataIndex: "status",
      key: "status",
      width: 100,
      render: (v: string | null | undefined) =>
        v ? <Tag color={v === "ACTIVE" ? "green" : "default"}>{v}</Tag> : "",
    },
    {
      title: t("items.effectiveFrom"),
      dataIndex: "effectiveFrom",
      key: "effectiveFrom",
      width: 130,
      render: (v: string | null | undefined) => v ?? "",
    },
    {
      title: t("items.effectiveTo"),
      dataIndex: "effectiveTo",
      key: "effectiveTo",
      width: 130,
      render: (v: string | null | undefined) => v ?? "",
    },
    {
      title: t("items.orderIndex"),
      dataIndex: "orderIndex",
      key: "orderIndex",
      width: 90,
    },
  ];

  const error = query.error;
  const errorMessage =
    error instanceof ApiError
      ? `${error.status}: ${error.message}`
      : error
        ? String(error)
        : null;

  return (
    <>
      <Card size="small" title={t("consumer.filters")} style={{ marginBottom: 16 }}>
        <Form
          form={form}
          layout="vertical"
          onFinish={apply}
          initialValues={{
            version: currentVersion ?? "",
            lang: "ru",
          }}
        >
          <Space wrap align="end" size={[12, 8]}>
            <Form.Item
              label={t("consumer.version")}
              name="version"
              extra={t("consumer.versionHint")}
              style={{ minWidth: 180 }}
            >
              <Input placeholder="published / 0.1.0" />
            </Form.Item>
            <Form.Item
              label={t("consumer.asOf")}
              name="asOf"
              extra={t("consumer.asOfHint")}
              style={{ minWidth: 180 }}
            >
              <DatePicker format="YYYY-MM-DD" style={{ width: "100%" }} />
            </Form.Item>
            <Form.Item
              label={t("consumer.knowledgeAsOf")}
              name="knowledgeAsOf"
              extra={t("consumer.knowledgeAsOfHint")}
              style={{ minWidth: 240 }}
            >
              <DatePicker showTime format="YYYY-MM-DD HH:mm:ss" style={{ width: "100%" }} />
            </Form.Item>
            <Form.Item label={t("consumer.lang")} name="lang" style={{ minWidth: 110 }}>
              <Select<Lang>
                options={[
                  { value: "ru", label: "ru" },
                  { value: "en", label: "en" },
                ]}
              />
            </Form.Item>
            <Form.Item style={{ marginBottom: 0 }}>
              <Space>
                <Button
                  type="primary"
                  htmlType="submit"
                  icon={<SearchOutlined />}
                  loading={query.isFetching}
                >
                  {t("consumer.apply")}
                </Button>
                <Button onClick={reset}>{t("consumer.reset")}</Button>
              </Space>
            </Form.Item>
          </Space>
          {(filters.asOf || filters.knowledgeAsOf) && (
            <Alert
              type="info"
              showIcon
              style={{ marginTop: 8 }}
              message={t("consumer.bitemporalActive", {
                asOf: filters.asOf ?? "—",
                knowledgeAsOf: filters.knowledgeAsOf ?? "—",
              })}
            />
          )}
        </Form>
      </Card>

      <Space style={{ marginBottom: 16 }} align="center" wrap>
        <Typography.Text type="secondary">{t("consumer.export")}:</Typography.Text>
        <Button
          icon={<FileExcelOutlined />}
          loading={exporting === "xlsx"}
          disabled={exporting !== null && exporting !== "xlsx"}
          onClick={() => doExport("xlsx")}
        >
          XLSX
        </Button>
        <Button
          icon={<DownloadOutlined />}
          loading={exporting === "csv"}
          disabled={exporting !== null && exporting !== "csv"}
          onClick={() => doExport("csv")}
        >
          CSV
        </Button>
        <Button
          icon={<DownloadOutlined />}
          loading={exporting === "json"}
          disabled={exporting !== null && exporting !== "json"}
          onClick={() => doExport("json")}
        >
          JSON
        </Button>
        <Typography.Text type="secondary">{t("consumer.exportHint")}</Typography.Text>
      </Space>

      {errorMessage && (
        <Alert type="error" message={errorMessage} showIcon style={{ marginBottom: 16 }} />
      )}

      {query.data && (
        <>
          <Descriptions
            size="small"
            column={3}
            bordered
            style={{ marginBottom: 16 }}
            items={[
              {
                key: "version",
                label: t("consumer.versionResolved"),
                children: <code>{query.data.version}</code>,
              },
              {
                key: "status",
                label: t("version.status"),
                children: <Tag>{query.data.status}</Tag>,
              },
              {
                key: "total",
                label: t("consumer.totalItems"),
                children: query.data.total,
              },
              {
                key: "publishedAt",
                label: t("version.publishedAt"),
                children: query.data.publishedAt
                  ? dayjs(query.data.publishedAt).format("YYYY-MM-DD HH:mm:ss")
                  : "—",
              },
              {
                key: "hash",
                label: t("version.contentHash"),
                span: 2,
                children: query.data.contentHash ? (
                  <Typography.Text code copyable={{ text: query.data.contentHash }}>
                    {query.data.contentHash.slice(0, 16)}…
                  </Typography.Text>
                ) : (
                  "—"
                ),
              },
            ]}
          />
          <Table<DistributionItem>
            rowKey={(r) =>
              Array.isArray(r.keyParts) ? r.keyParts.join("|") : String(r.keyParts)
            }
            dataSource={query.data.items}
            columns={columns}
            size="small"
            pagination={{ pageSize: 50, showSizeChanger: true }}
            scroll={{ x: "max-content", y: 480 }}
            loading={query.isFetching}
          />
        </>
      )}

      {!query.data && !errorMessage && query.isFetching && (
        <Typography.Text type="secondary">{t("common.loading")}</Typography.Text>
      )}
    </>
  );
}

function formatAttr(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") {
    return String(v);
  }
  return JSON.stringify(v);
}
