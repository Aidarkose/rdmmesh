import { useState } from "react";
import {
  Alert,
  Button,
  Modal,
  Space,
  Statistic,
  Table,
  Tabs,
  Typography,
  Upload,
  App as AntApp,
  type TableColumnsType,
  type UploadFile,
} from "antd";
import {
  FileExcelOutlined,
  ImportOutlined,
  InboxOutlined,
} from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiMutations, type NewItemBody } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";
import type { BulkImportError, BulkImportResult } from "@/api/types";

interface Props {
  versionId: string;
  codesetId: string;
}

const JSON_PLACEHOLDER = `[
  {"key_parts": ["S1"], "label_ru": "Стадия 1", "label_en": "Stage 1", "attributes": {"stage": "1"}},
  {"key_parts": ["S2"], "label_ru": "Стадия 2", "label_en": "Stage 2", "attributes": {"stage": "2"}}
]`;

const CSV_PLACEHOLDER = `key_parts,label_ru,label_en,attributes
S1,Стадия 1,Stage 1,"{""stage"":""1""}"
S2,Стадия 2,Stage 2,"{""stage"":""2""}"`;

function parseJsonRows(raw: string): NewItemBody[] {
  const trimmed = raw.trim();
  if (!trimmed) throw new SyntaxError("body is empty");
  const parsed: unknown = JSON.parse(trimmed);
  if (!Array.isArray(parsed)) {
    throw new SyntaxError("expected JSON array of items");
  }
  // Лёгкая sanity-проверка: каждая строка — объект; key_parts — массив либо строка.
  return parsed.map((row, idx) => {
    if (typeof row !== "object" || row === null || Array.isArray(row)) {
      throw new SyntaxError(`row ${idx}: must be an object`);
    }
    const r = row as Record<string, unknown>;
    if (!r.key_parts) throw new SyntaxError(`row ${idx}: key_parts is required`);
    if (!Array.isArray(r.key_parts) && typeof r.key_parts !== "string") {
      throw new SyntaxError(`row ${idx}: key_parts must be array or string`);
    }
    return {
      ...r,
      key_parts: Array.isArray(r.key_parts) ? r.key_parts : [r.key_parts],
    } as NewItemBody;
  });
}

export function BulkImportButton({ versionId, codesetId }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  return (
    <>
      <Button icon={<ImportOutlined />} onClick={() => setOpen(true)}>
        {t("bulk.button")}
      </Button>
      <BulkImportModal
        open={open}
        onClose={() => setOpen(false)}
        versionId={versionId}
        codesetId={codesetId}
      />
    </>
  );
}

function BulkImportModal({
  open,
  onClose,
  versionId,
  codesetId,
}: Props & { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const [tab, setTab] = useState<"json" | "csv" | "xlsx">("json");
  const [jsonText, setJsonText] = useState("");
  const [csvText, setCsvText] = useState("");
  const [xlsxFile, setXlsxFile] = useState<File | null>(null);
  const [parseError, setParseError] = useState<string | null>(null);
  const [result, setResult] = useState<BulkImportResult | null>(null);

  const reset = () => {
    setJsonText("");
    setCsvText("");
    setXlsxFile(null);
    setParseError(null);
    setResult(null);
    setTab("json");
  };

  const close = () => {
    reset();
    onClose();
  };

  const onSuccess = (res: BulkImportResult) => {
    setResult(res);
    setParseError(null);
    if (res.status === "APPLIED") {
      message.success(
        t("bulk.applied", {
          added: res.rows_added ?? 0,
          updated: res.rows_updated ?? 0,
          unchanged: res.rows_unchanged ?? 0,
        }),
      );
      queryClient.invalidateQueries({ queryKey: qk.versions.itemsRoot(versionId) });
      queryClient.invalidateQueries({ queryKey: qk.versions.one(versionId) });
      queryClient.invalidateQueries({ queryKey: qk.versions.byCodeset(codesetId) });
    }
    // REJECTED: модал не закрываем — пользователь видит errors[] и может править payload.
  };

  const onError = (e: unknown) => {
    if (e instanceof SyntaxError) {
      setParseError(e.message);
      return;
    }
    if (e instanceof ApiError && e.status === 422) {
      // backend может также вернуть 422 по другим причинам — например, status='APPLIED'
      // приходит с HTTP 200, но REJECTED с HTTP 422. Тело у нас — BulkImportResult.
      // ApiError.body — это распаршеный JSON; мы покажем его в setResult, если совпадает по форме.
      const body = e.body as Partial<BulkImportResult> | null;
      if (body && body.status === "REJECTED") {
        setResult(body as BulkImportResult);
        setParseError(null);
        return;
      }
    }
    const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
    message.error(msg);
  };

  const importJson = useMutation({
    mutationFn: (raw: string) => apiMutations.bulkJson(versionId, parseJsonRows(raw)),
    onSuccess,
    onError,
  });

  const importCsv = useMutation({
    mutationFn: (raw: string) => apiMutations.bulkCsv(versionId, raw),
    onSuccess,
    onError,
  });

  const importXlsx = useMutation({
    mutationFn: (file: File) => apiMutations.bulkXlsx(versionId, file),
    onSuccess,
    onError,
  });

  const submit = () => {
    setParseError(null);
    setResult(null);
    if (tab === "json") {
      if (!jsonText.trim()) {
        setParseError(t("bulk.empty"));
        return;
      }
      importJson.mutate(jsonText);
    } else if (tab === "csv") {
      if (!csvText.trim()) {
        setParseError(t("bulk.empty"));
        return;
      }
      importCsv.mutate(csvText);
    } else {
      if (!xlsxFile) {
        setParseError(t("bulk.xlsxNoFile"));
        return;
      }
      importXlsx.mutate(xlsxFile);
    }
  };

  const onFileSelected = (file: UploadFile<unknown>): boolean => {
    const obj = file.originFileObj ?? (file as unknown as File);
    if (!(obj instanceof Blob)) return false;
    if (tab === "xlsx") {
      // XLSX — бинарь, не читаем text(): отправляем File как есть.
      setXlsxFile(obj as File);
      return false;
    }
    void obj.text().then((text) => {
      if (tab === "json") setJsonText(text);
      else setCsvText(text);
    });
    return false; // НЕ вызывать default upload — мы только читаем содержимое.
  };

  const isPending =
    importJson.isPending || importCsv.isPending || importXlsx.isPending;

  return (
    <Modal
      open={open}
      title={t("bulk.title")}
      okText={t("bulk.submit")}
      cancelText={t("workflow.modal.cancel")}
      confirmLoading={isPending}
      onOk={submit}
      onCancel={close}
      width={780}
      destroyOnClose
    >
      <Tabs
        activeKey={tab}
        onChange={(k) => setTab(k as "json" | "csv" | "xlsx")}
        items={[
          {
            key: "json",
            label: "JSON",
            children: (
              <Space direction="vertical" style={{ width: "100%" }}>
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  {t("bulk.jsonHint")}
                </Typography.Paragraph>
                <Upload.Dragger
                  accept=".json,application/json"
                  multiple={false}
                  showUploadList={false}
                  beforeUpload={(file) => onFileSelected(file as unknown as UploadFile<unknown>)}
                  style={{ padding: 12 }}
                >
                  <p style={{ margin: 0 }}>
                    <InboxOutlined style={{ fontSize: 24 }} />
                    <span style={{ marginLeft: 8 }}>{t("bulk.dropJson")}</span>
                  </p>
                </Upload.Dragger>
                <Typography.Text type="secondary">{t("bulk.orPaste")}</Typography.Text>
                <textarea
                  value={jsonText}
                  onChange={(e) => setJsonText(e.target.value)}
                  placeholder={JSON_PLACEHOLDER}
                  rows={10}
                  style={{
                    width: "100%",
                    fontFamily: "monospace",
                    fontSize: 12,
                    padding: 8,
                    borderRadius: 4,
                    border: "1px solid #d9d9d9",
                  }}
                />
              </Space>
            ),
          },
          {
            key: "csv",
            label: "CSV",
            children: (
              <Space direction="vertical" style={{ width: "100%" }}>
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  {t("bulk.csvHint")}
                </Typography.Paragraph>
                <Upload.Dragger
                  accept=".csv,text/csv"
                  multiple={false}
                  showUploadList={false}
                  beforeUpload={(file) => onFileSelected(file as unknown as UploadFile<unknown>)}
                  style={{ padding: 12 }}
                >
                  <p style={{ margin: 0 }}>
                    <InboxOutlined style={{ fontSize: 24 }} />
                    <span style={{ marginLeft: 8 }}>{t("bulk.dropCsv")}</span>
                  </p>
                </Upload.Dragger>
                <Typography.Text type="secondary">{t("bulk.orPaste")}</Typography.Text>
                <textarea
                  value={csvText}
                  onChange={(e) => setCsvText(e.target.value)}
                  placeholder={CSV_PLACEHOLDER}
                  rows={10}
                  style={{
                    width: "100%",
                    fontFamily: "monospace",
                    fontSize: 12,
                    padding: 8,
                    borderRadius: 4,
                    border: "1px solid #d9d9d9",
                  }}
                />
              </Space>
            ),
          },
          {
            key: "xlsx",
            label: "Excel (XLSX)",
            children: (
              <Space direction="vertical" style={{ width: "100%" }}>
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  {t("bulk.xlsxHint")}
                </Typography.Paragraph>
                <Upload.Dragger
                  accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                  multiple={false}
                  showUploadList={false}
                  beforeUpload={(file) => onFileSelected(file as unknown as UploadFile<unknown>)}
                  style={{ padding: 12 }}
                >
                  <p style={{ margin: 0 }}>
                    <FileExcelOutlined style={{ fontSize: 24 }} />
                    <span style={{ marginLeft: 8 }}>{t("bulk.dropXlsx")}</span>
                  </p>
                </Upload.Dragger>
                {xlsxFile && (
                  <Typography.Text type="success">
                    {t("bulk.xlsxSelected", { name: xlsxFile.name })}
                  </Typography.Text>
                )}
              </Space>
            ),
          },
        ]}
      />

      {parseError && (
        <Alert
          type="error"
          showIcon
          style={{ marginTop: 12 }}
          message={t("bulk.parseError")}
          description={parseError}
        />
      )}

      {result && <ResultPanel result={result} />}
    </Modal>
  );
}

function ResultPanel({ result }: { result: BulkImportResult }) {
  const { t } = useTranslation();
  const errorColumns: TableColumnsType<BulkImportError> = [
    { title: "row", dataIndex: "row_index", key: "row_index", width: 60 },
    {
      title: "key",
      dataIndex: "key_parts",
      key: "key_parts",
      width: 160,
      render: (v: string[] | null | undefined) =>
        v ? <code>{v.join(" / ")}</code> : "",
    },
    { title: "field", dataIndex: "field", key: "field", width: 120 },
    { title: "message", dataIndex: "message", key: "message" },
  ];
  const isApplied = result.status === "APPLIED";
  return (
    <div style={{ marginTop: 16 }}>
      <Alert
        type={isApplied ? "success" : "error"}
        showIcon
        message={isApplied ? t("bulk.appliedTitle") : t("bulk.rejectedTitle")}
      />
      <Space style={{ marginTop: 12 }} wrap>
        <Statistic title="total" value={result.rows_total} />
        <Statistic title="added" value={result.rows_added ?? 0} />
        <Statistic title="updated" value={result.rows_updated ?? 0} />
        <Statistic title="unchanged" value={result.rows_unchanged ?? 0} />
        <Statistic title="errors" value={result.errors?.length ?? 0} valueStyle={{ color: "#cf1322" }} />
      </Space>
      {result.errors && result.errors.length > 0 && (
        <Table<BulkImportError>
          rowKey={(r, i) => `${r.row_index}-${i}`}
          dataSource={result.errors}
          columns={errorColumns}
          size="small"
          pagination={{ pageSize: 10 }}
          style={{ marginTop: 12 }}
        />
      )}
    </div>
  );
}
