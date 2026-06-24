import { useMemo, useState } from "react";
import {
  Button,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  App as AntApp,
  type TableColumnsType,
} from "antd";
import {
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  EditOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import dayjs, { type Dayjs } from "dayjs";

import type { CodeItem, CodeItemStatus } from "@/api/types";
import { apiMutations, type ItemPatchBody } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  items: CodeItem[];
  // E11.2b: inline edit включается только для DRAFT-версий.
  editable?: boolean;
  versionId?: string;
  codesetId?: string;
}

interface EditFormValues {
  labelRu?: string | null;
  labelEn?: string | null;
  descriptionRu?: string | null;
  descriptionEn?: string | null;
  orderIndex?: number | null;
  attributesRaw?: string | null;
  // E13 — bitemporal + hierarchy
  status?: CodeItemStatus | null;
  effectiveFrom?: Dayjs | null;
  effectiveTo?: Dayjs | null;
  parentKeyRaw?: string | null;
}

function parseAttributes(raw: string | undefined | null): Record<string, unknown> | null {
  if (!raw || !raw.trim()) return null;
  const parsed: unknown = JSON.parse(raw);
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new SyntaxError("attributes must be a JSON object");
  }
  return parsed as Record<string, unknown>;
}

// E13: парсит parent_key из строки. Пустая → null (узел становится корневым).
// "DEPT" → ["DEPT"]; JSON-array ["DEPT","DIV"] → как есть.
function parseParentKey(raw: string | undefined | null): string[] | null {
  if (raw == null) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith("[")) {
    const parsed: unknown = JSON.parse(trimmed);
    if (!Array.isArray(parsed)) throw new SyntaxError("parent_key must be a JSON array");
    return parsed.map((p) => String(p));
  }
  return [trimmed];
}

function formatParentKey(pk: string[] | null | undefined): string {
  if (!pk || pk.length === 0) return "";
  return pk.length === 1 ? pk[0] : JSON.stringify(pk);
}

// E23: клиентский поиск по записям. Собираем все значимые поля строки в одну
// строку и ищем подстроку без учёта регистра. Покрываем ключ, parent_key,
// label/description (ru/en), статус, order_index и значения всех атрибутов.
function itemMatchesQuery(it: CodeItem, query: string): boolean {
  if (!query) return true;
  const haystack: string[] = [
    Array.isArray(it.key_parts) ? it.key_parts.join(" ") : String(it.key_parts ?? ""),
    formatParentKey(it.parent_key),
    it.label_ru ?? "",
    it.label_en ?? "",
    it.description_ru ?? "",
    it.description_en ?? "",
    it.status ?? "",
    it.order_index != null ? String(it.order_index) : "",
  ];
  if (it.attributes) {
    for (const [k, v] of Object.entries(it.attributes)) {
      haystack.push(k, formatAttr(v));
    }
  }
  return haystack.join("").toLowerCase().includes(query);
}

export function ItemsTable({ items, editable = false, versionId, codesetId }: Props) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  // E23: выбор нескольких строк для массового удаления (только DRAFT).
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [form] = Form.useForm<EditFormValues>();

  // Сбросить cache invalidation после mutation'ов; reused и patch'ем, и delete'ом.
  const invalidate = () => {
    if (!versionId) return;
    queryClient.invalidateQueries({ queryKey: qk.versions.itemsRoot(versionId) });
    queryClient.invalidateQueries({ queryKey: qk.versions.one(versionId) });
    if (codesetId) {
      queryClient.invalidateQueries({ queryKey: qk.versions.byCodeset(codesetId) });
    }
  };

  const patch = useMutation({
    mutationFn: ({ itemId, body }: { itemId: string; body: ItemPatchBody }) =>
      apiMutations.patchItem(versionId!, itemId, body),
    onSuccess: () => {
      message.success(t("items.editSuccess"));
      setEditingId(null);
      form.resetFields();
      invalidate();
    },
    onError: (e: unknown) => {
      // 409 = optimistic lock OR DRAFT-only check; 422 = schema validation
      if (e instanceof ApiError && e.status === 409) {
        message.error(t("items.optimisticConflict"));
        // подтянем свежие данные — пользователь увидит актуальный row_version
        invalidate();
        setEditingId(null);
        form.resetFields();
        return;
      }
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const remove = useMutation({
    mutationFn: (itemId: string) => apiMutations.deleteItem(versionId!, itemId),
    onSuccess: () => {
      message.success(t("items.deleteSuccess"));
      invalidate();
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  // E23: массовое удаление выбранных записей. Bulk-by-ids эндпоинта нет, поэтому
  // шлём DELETE по одной; собираем число успехов/ошибок, чтобы показать итог.
  const bulkRemove = useMutation({
    mutationFn: async (ids: React.Key[]) => {
      let ok = 0;
      let failed = 0;
      for (const id of ids) {
        try {
          await apiMutations.deleteItem(versionId!, String(id));
          ok += 1;
        } catch {
          failed += 1;
        }
      }
      return { ok, failed };
    },
    onSuccess: ({ ok, failed }) => {
      if (failed > 0) {
        message.warning(t("items.bulkDelete.partial", { ok, failed }));
      } else {
        message.success(t("items.bulkDelete.success", { n: ok }));
      }
      setSelectedRowKeys([]);
      invalidate();
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const startEdit = (it: CodeItem) => {
    form.setFieldsValue({
      labelRu: it.label_ru ?? "",
      labelEn: it.label_en ?? "",
      descriptionRu: it.description_ru ?? "",
      descriptionEn: it.description_en ?? "",
      orderIndex: it.order_index ?? null,
      attributesRaw: it.attributes ? JSON.stringify(it.attributes, null, 2) : "",
      status: (it.status as CodeItemStatus | null) ?? "ACTIVE",
      effectiveFrom: it.effective_from ? dayjs(it.effective_from) : null,
      effectiveTo: it.effective_to ? dayjs(it.effective_to) : null,
      parentKeyRaw: formatParentKey(it.parent_key),
    });
    setEditingId(it.id);
  };

  const cancelEdit = () => {
    setEditingId(null);
    form.resetFields();
  };

  const saveEdit = async (it: CodeItem) => {
    let values: EditFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    let attributes: Record<string, unknown> | null;
    let parentKey: string[] | null;
    try {
      attributes = parseAttributes(values.attributesRaw ?? "");
      parentKey = parseParentKey(values.parentKeyRaw);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      message.error(`${t("items.parseError")}: ${msg}`);
      return;
    }
    if (it.row_version == null) {
      message.error("row_version отсутствует — обновите страницу");
      return;
    }
    patch.mutate({
      itemId: it.id,
      body: {
        expected_row_version: it.row_version,
        label_ru: values.labelRu?.trim() || null,
        label_en: values.labelEn?.trim() || null,
        description_ru: values.descriptionRu?.trim() || null,
        description_en: values.descriptionEn?.trim() || null,
        order_index: values.orderIndex ?? null,
        attributes,
        status: values.status ?? null,
        effective_from: values.effectiveFrom ? values.effectiveFrom.format("YYYY-MM-DD") : null,
        effective_to: values.effectiveTo ? values.effectiveTo.format("YYYY-MM-DD") : null,
        parent_key: parentKey,
      },
    });
  };

  const attrKeys = useMemo(() => {
    const keys = new Set<string>();
    for (const it of items) {
      if (it.attributes) {
        for (const k of Object.keys(it.attributes)) keys.add(k);
      }
    }
    return Array.from(keys).sort();
  }, [items]);

  // E23: отфильтрованный набор для отрисовки. Редактируемую строку не прячем,
  // даже если она перестала матчить запрос, — иначе inline-edit «исчезнет».
  const filteredItems = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return items;
    return items.filter((it) => it.id === editingId || itemMatchesQuery(it, q));
  }, [items, search, editingId]);

  const columns: TableColumnsType<CodeItem> = useMemo(() => {
    const isEditing = (id: string) => editingId === id;
    const renderEdit = (input: React.ReactNode, id: string, fallback: React.ReactNode) =>
      isEditing(id) ? input : fallback;

    const base: TableColumnsType<CodeItem> = [
      {
        title: t("items.key"),
        key: "key",
        fixed: "left",
        render: (_, row) => (
          <code>
            {Array.isArray(row.key_parts) ? row.key_parts.join(" / ") : String(row.key_parts)}
          </code>
        ),
        width: 200,
      },
      {
        title: t("items.parentKey"),
        key: "parent_key",
        width: 160,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="parentKeyRaw" style={{ margin: 0 }} extra={t("items.parentKeyHint")}>
              <Input size="small" placeholder='DEPT / ["DEPT","DIV"]' />
            </Form.Item>,
            row.id,
            row.parent_key && row.parent_key.length > 0 ? (
              <code>{formatParentKey(row.parent_key)}</code>
            ) : (
              ""
            ),
          ),
      },
      {
        title: t("items.labelRu"),
        key: "label_ru",
        width: 180,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="labelRu" style={{ margin: 0 }}>
              <Input size="small" />
            </Form.Item>,
            row.id,
            row.label_ru ?? "",
          ),
      },
      {
        title: t("items.labelEn"),
        key: "label_en",
        width: 180,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="labelEn" style={{ margin: 0 }}>
              <Input size="small" />
            </Form.Item>,
            row.id,
            row.label_en ?? "",
          ),
      },
    ];

    // Атрибуты: в режиме редактирования прячем разнесённые колонки и показываем
    // одну textarea-колонку attributesRaw (JSON). В обычном режиме — колонки
    // на каждый ключ (как round 1).
    const attrs: TableColumnsType<CodeItem> = editingId
      ? [
          {
            title: t("items.attributes"),
            key: "attributes_edit",
            width: 280,
            render: (_, row) =>
              renderEdit(
                <Form.Item
                  name="attributesRaw"
                  style={{ margin: 0 }}
                  extra={t("items.attributesHint")}
                >
                  <Input.TextArea rows={3} size="small" placeholder='{"stage":"1"}' />
                </Form.Item>,
                row.id,
                row.attributes ? <code>{JSON.stringify(row.attributes)}</code> : "",
              ),
          },
        ]
      : attrKeys.map((k) => ({
          title: k,
          key: `attr.${k}`,
          render: (_, row) => formatAttr(row.attributes?.[k]),
          width: 140,
        }));

    const tail: TableColumnsType<CodeItem> = [
      {
        title: t("items.orderIndex"),
        key: "order_index",
        width: 110,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="orderIndex" style={{ margin: 0 }}>
              <InputNumber size="small" min={0} style={{ width: "100%" }} />
            </Form.Item>,
            row.id,
            row.order_index ?? "",
          ),
      },
      {
        title: t("items.status"),
        key: "status",
        width: 120,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="status" style={{ margin: 0 }}>
              <Select<CodeItemStatus>
                size="small"
                style={{ width: "100%" }}
                options={[
                  { value: "ACTIVE", label: t("items.statusValues.ACTIVE") },
                  { value: "RETIRED", label: t("items.statusValues.RETIRED") },
                ]}
              />
            </Form.Item>,
            row.id,
            row.status ? (
              <Tag color={row.status === "ACTIVE" ? "green" : "default"}>
                {t(`items.statusValues.${row.status}`)}
              </Tag>
            ) : (
              ""
            ),
          ),
      },
      {
        title: t("items.effectiveFrom"),
        key: "effective_from",
        width: 150,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="effectiveFrom" style={{ margin: 0 }}>
              <DatePicker size="small" style={{ width: "100%" }} format="YYYY-MM-DD" />
            </Form.Item>,
            row.id,
            row.effective_from ?? "",
          ),
      },
      {
        title: t("items.effectiveTo"),
        key: "effective_to",
        width: 150,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="effectiveTo" style={{ margin: 0 }}>
              <DatePicker size="small" style={{ width: "100%" }} format="YYYY-MM-DD" />
            </Form.Item>,
            row.id,
            row.effective_to ?? "",
          ),
      },
      { title: t("items.rowVersion"), dataIndex: "row_version", key: "row_version", width: 100 },
    ];

    if (!editable) {
      return [...base, ...attrs, ...tail];
    }

    const actions: TableColumnsType<CodeItem> = [
      {
        title: t("items.actions"),
        key: "actions",
        fixed: "right",
        width: 140,
        render: (_, row) =>
          isEditing(row.id) ? (
            <Space size={4}>
              <Tooltip title={t("items.save")}>
                <Button
                  type="primary"
                  size="small"
                  icon={<CheckOutlined />}
                  loading={patch.isPending}
                  onClick={() => void saveEdit(row)}
                />
              </Tooltip>
              <Tooltip title={t("items.cancel")}>
                <Button size="small" icon={<CloseOutlined />} onClick={cancelEdit} />
              </Tooltip>
            </Space>
          ) : (
            <Space size={4}>
              <Tooltip title={t("items.edit")}>
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  disabled={editingId !== null || remove.isPending}
                  onClick={() => startEdit(row)}
                />
              </Tooltip>
              <Popconfirm
                title={t("items.deleteConfirmTitle")}
                description={t("items.deleteConfirmDescription")}
                okText={t("version.deleteConfirmOk")}
                okButtonProps={{ danger: true }}
                cancelText={t("workflow.modal.cancel")}
                onConfirm={() => remove.mutate(row.id)}
              >
                <Tooltip title={t("items.deleteRow")}>
                  <Button
                    danger
                    size="small"
                    icon={<DeleteOutlined />}
                    disabled={editingId !== null}
                  />
                </Tooltip>
              </Popconfirm>
            </Space>
          ),
      },
    ];

    return [...base, ...attrs, ...tail, ...actions];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t, attrKeys, editingId, editable, patch.isPending, remove.isPending]);

  // E13: expand-row показывает description_ru/en — в режиме редактирования это
  // редактируемые textarea, в read-mode — текст.
  const expandedRowRender = (row: CodeItem) => {
    if (editingId === row.id) {
      return (
        <Space direction="vertical" style={{ width: "100%" }}>
          <Form.Item
            label={t("items.descriptionRu")}
            name="descriptionRu"
            style={{ marginBottom: 8 }}
          >
            <Input.TextArea rows={2} placeholder={t("items.descriptionHint")} />
          </Form.Item>
          <Form.Item
            label={t("items.descriptionEn")}
            name="descriptionEn"
            style={{ marginBottom: 0 }}
          >
            <Input.TextArea rows={2} placeholder={t("items.descriptionHint")} />
          </Form.Item>
        </Space>
      );
    }
    const hasDescription = row.description_ru || row.description_en;
    if (!hasDescription) {
      return (
        <span style={{ color: "#888", fontSize: 12 }}>{t("items.descriptionEmpty")}</span>
      );
    }
    return (
      <Space direction="vertical" size={4}>
        {row.description_ru && (
          <div>
            <Tag>ru</Tag> {row.description_ru}
          </div>
        )}
        {row.description_en && (
          <div>
            <Tag>en</Tag> {row.description_en}
          </div>
        )}
      </Space>
    );
  };

  const expandable = {
    expandedRowRender,
    // Если идёт edit — раскрываем эту строку принудительно, чтобы description-textareas были видны.
    expandedRowKeys: editingId ? [editingId] : undefined,
    rowExpandable: () => true,
  };

  // E23: выбор строк галочками доступен только в editable (DRAFT) режиме.
  // Чекбокс редактируемой строки блокируем, чтобы не удалить её во время правки.
  const rowSelection = editable
    ? {
        selectedRowKeys,
        onChange: (keys: React.Key[]) => setSelectedRowKeys(keys),
        getCheckboxProps: (row: CodeItem) => ({
          disabled: editingId !== null && editingId !== row.id,
        }),
      }
    : undefined;

  const toolbar = (
    <div
      style={{
        marginBottom: 12,
        display: "flex",
        alignItems: "center",
        gap: 12,
        flexWrap: "wrap",
      }}
    >
      <Input
        allowClear
        size="small"
        prefix={<SearchOutlined />}
        placeholder={t("items.searchPlaceholder")}
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        style={{ maxWidth: 360 }}
      />
      {editable && selectedRowKeys.length > 0 && (
        <Space size={8}>
          <span>{t("items.bulkDelete.selected", { n: selectedRowKeys.length })}</span>
          <Popconfirm
            title={t("items.bulkDelete.confirmTitle")}
            description={t("items.bulkDelete.confirmDescription", { n: selectedRowKeys.length })}
            okText={t("version.deleteConfirmOk")}
            okButtonProps={{ danger: true }}
            cancelText={t("workflow.modal.cancel")}
            onConfirm={() => bulkRemove.mutate(selectedRowKeys)}
          >
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              loading={bulkRemove.isPending}
              disabled={editingId !== null}
            >
              {t("items.bulkDelete.button")}
            </Button>
          </Popconfirm>
          <Button size="small" onClick={() => setSelectedRowKeys([])}>
            {t("items.bulkDelete.clearSelection")}
          </Button>
        </Space>
      )}
    </div>
  );

  const table = (
    <Table<CodeItem>
      rowKey={(r) => r.id}
      dataSource={filteredItems}
      columns={columns}
      size="small"
      pagination={false}
      scroll={{ x: "max-content", y: 480 }}
      sticky
      expandable={expandable}
      rowSelection={rowSelection}
    />
  );

  const content = (
    <>
      {toolbar}
      {table}
    </>
  );

  if (!editable) return content;
  return <Form form={form}>{content}</Form>;
}

function formatAttr(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") {
    return String(v);
  }
  return JSON.stringify(v);
}
