import { useState } from "react";
import {
  Alert,
  Button,
  Modal,
  Radio,
  Select,
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

// E19 Commit 3 — Pivot Matrix import. Таб доступен для ЛЮБОГО CodeSet —
// пользователь сам решает, подходит ли его файл для pivot-раскладки. Если
// CodeSet одноключевой, а pivot-парсер создаёт triples [from, to, horizon] —
// backend вернёт validation error (key_parts size mismatch). Это лучше, чем
// прятать таб и заставлять пользователя угадывать про скрытые теги.
const PIVOT_HORIZONS = ["1M", "3M", "6M", "1Y", "3Y", "5Y"] as const;
type PivotResidualPolicy = "implicit_default" | "strict" | "free";

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

type ImportTabKey = "json" | "csv" | "xlsx" | "pivot";

function BulkImportModal({
  open,
  onClose,
  versionId,
  codesetId,
}: Props & { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const pivotAvailable = true;
  const [tab, setTab] = useState<ImportTabKey>("json");
  const [jsonText, setJsonText] = useState("");
  const [csvText, setCsvText] = useState("");
  const [xlsxFile, setXlsxFile] = useState<File | null>(null);
  const [pivotFile, setPivotFile] = useState<File | null>(null);
  const [pivotHorizon, setPivotHorizon] = useState<string>("1Y");
  const [pivotPolicy, setPivotPolicy] = useState<PivotResidualPolicy>("implicit_default");
  const [parseError, setParseError] = useState<string | null>(null);
  const [result, setResult] = useState<BulkImportResult | null>(null);

  const reset = () => {
    setJsonText("");
    setCsvText("");
    setXlsxFile(null);
    setPivotFile(null);
    setPivotHorizon("1Y");
    setPivotPolicy("implicit_default");
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

  const importPivot = useMutation({
    mutationFn: (args: { file: File; horizon: string; policy: PivotResidualPolicy }) =>
      apiMutations.bulkXlsxPivot(versionId, args.file, {
        horizon: args.horizon,
        rowResidualPolicy: args.policy,
      }),
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
    } else if (tab === "xlsx") {
      if (!xlsxFile) {
        setParseError(t("bulk.xlsxNoFile"));
        return;
      }
      importXlsx.mutate(xlsxFile);
    } else {
      // pivot
      if (!pivotFile) {
        setParseError(t("bulk.xlsxNoFile"));
        return;
      }
      if (!pivotHorizon) {
        setParseError(t("bulk.pivot.horizonRequired"));
        return;
      }
      importPivot.mutate({ file: pivotFile, horizon: pivotHorizon, policy: pivotPolicy });
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
    if (tab === "pivot") {
      setPivotFile(obj as File);
      return false;
    }
    void obj.text().then((text) => {
      if (tab === "json") setJsonText(text);
      else setCsvText(text);
    });
    return false; // НЕ вызывать default upload — мы только читаем содержимое.
  };

  const isPending =
    importJson.isPending ||
    importCsv.isPending ||
    importXlsx.isPending ||
    importPivot.isPending;

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
        onChange={(k) => setTab(k as ImportTabKey)}
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
          // E19 Commit 3 — Pivot Matrix tab (только для transition_matrix CodeSet'ов).
          ...(pivotAvailable
            ? [
                {
                  key: "pivot" as ImportTabKey,
                  label: t("bulk.pivot.tabLabel"),
                  children: (
                    <Space direction="vertical" style={{ width: "100%" }}>
                      <Alert
                        type="info"
                        showIcon
                        message={t("bulk.pivot.intro")}
                        description={t("bulk.pivot.format")}
                      />
                      <Space>
                        <Typography.Text strong>{t("bulk.pivot.horizon")}:</Typography.Text>
                        <Select<string>
                          value={pivotHorizon}
                          onChange={(v) => setPivotHorizon(v)}
                          style={{ minWidth: 90 }}
                          options={PIVOT_HORIZONS.map((h) => ({ label: h, value: h }))}
                        />
                      </Space>
                      <Space direction="vertical" style={{ width: "100%" }}>
                        <Typography.Text strong>{t("bulk.pivot.residual")}:</Typography.Text>
                        <Radio.Group
                          value={pivotPolicy}
                          onChange={(e) => setPivotPolicy(e.target.value as PivotResidualPolicy)}
                        >
                          <Space direction="vertical">
                            <Radio value="implicit_default">
                              {t("bulk.pivot.implicitDefault")}
                              <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                                ({t("bulk.pivot.implicitDefaultHint")})
                              </Typography.Text>
                            </Radio>
                            <Radio value="strict">
                              {t("bulk.pivot.strict")}
                              <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                                ({t("bulk.pivot.strictHint")})
                              </Typography.Text>
                            </Radio>
                            <Radio value="free">
                              {t("bulk.pivot.free")}
                              <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                                ({t("bulk.pivot.freeHint")})
                              </Typography.Text>
                            </Radio>
                          </Space>
                        </Radio.Group>
                      </Space>
                      <Upload.Dragger
                        accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        multiple={false}
                        showUploadList={false}
                        beforeUpload={(file) =>
                          onFileSelected(file as unknown as UploadFile<unknown>)
                        }
                        style={{ padding: 12 }}
                      >
                        <p style={{ margin: 0 }}>
                          <FileExcelOutlined style={{ fontSize: 24 }} />
                          <span style={{ marginLeft: 8 }}>{t("bulk.dropXlsx")}</span>
                        </p>
                      </Upload.Dragger>
                      {pivotFile && (
                        <Typography.Text type="success">
                          {t("bulk.xlsxSelected", { name: pivotFile.name })}
                        </Typography.Text>
                      )}
                    </Space>
                  ),
                },
              ]
            : []),
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
        {/* E19 — pivot import: сколько ячеек absorbing-колонки дописал парсер. */}
        {result.implicitDefaultAdded != null && result.implicitDefaultAdded > 0 && (
          <Statistic
            title={t("bulk.pivot.implicitDefaultAddedLabel")}
            value={result.implicitDefaultAdded}
            valueStyle={{ color: "#1677ff" }}
          />
        )}
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
