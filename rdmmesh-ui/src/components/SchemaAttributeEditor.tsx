import { useMemo, useState } from "react";
import {
  App as AntApp,
  Button,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tag,
  Typography,
} from "antd";
import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  codesetId: string;
  jsonSchema: Record<string, unknown>;
  // добавлять/удалять поля могут только Schema Designer / Admin (бэкенд: @RolesAllowed).
  canEdit: boolean;
}

// STANDARD-колонки + служебные имена, которые нельзя занимать атрибутом — они уже
// существуют у каждого справочника как отдельные поля (RelationalDdlBuilder.withStandard)
// либо приходят из key_spec/системы. Совпадение имени сломало бы проекцию в rd_data.
const RESERVED = new Set<string>([
  "id",
  "key_parts",
  "parent_key",
  "parent_ref",
  "label_ru",
  "label_en",
  "description_ru",
  "description_en",
  "order_index",
  "status",
  "effective_from",
  "effective_to",
  "system_from",
  "system_to",
  "row_version",
  "version_id",
]);

// Логический тип в UI → фрагмент JSON Schema property. Зеркалит RelationalTypes
// на бэкенде (number→double precision, integer→bigint, string+enum→text, date→date).
type LogicalType = "number" | "integer" | "string" | "boolean" | "date" | "enum";

function buildProperty(type: LogicalType, enumValues: string[]): Record<string, unknown> {
  switch (type) {
    case "number":
      return { type: "number" };
    case "integer":
      return { type: "integer" };
    case "boolean":
      return { type: "boolean" };
    case "date":
      return { type: "string", format: "date" };
    case "enum":
      return { type: "string", enum: enumValues };
    case "string":
    default:
      return { type: "string" };
  }
}

// property def → ключ перевода для человекочитаемого ярлыка типа.
function typeLabelKey(prop: Record<string, unknown>): string {
  if (Array.isArray(prop.enum)) return "enum";
  const t = typeof prop.type === "string" ? prop.type : "string";
  if (t === "number") return "number";
  if (t === "integer") return "integer";
  if (t === "boolean") return "boolean";
  if (t === "object" || t === "array") return "json";
  if (t === "string" && prop.format === "date") return "date";
  return "string";
}

interface AttrRow {
  name: string;
  prop: Record<string, unknown>;
  enumValues: string[] | null;
}

function readAttributes(schema: Record<string, unknown>): AttrRow[] {
  const props = schema.properties;
  if (!props || typeof props !== "object" || Array.isArray(props)) return [];
  return Object.entries(props as Record<string, unknown>).map(([name, raw]) => {
    const prop = (raw && typeof raw === "object" && !Array.isArray(raw)
      ? raw
      : {}) as Record<string, unknown>;
    return {
      name,
      prop,
      enumValues: Array.isArray(prop.enum)
        ? (prop.enum as unknown[]).map((x) => String(x))
        : null,
    };
  });
}

/**
 * Добавление / удаление атрибутов CodeSetSchema «мышкой». Это надстройка над тем же
 * PUT /codesets/{id}/schema, что использует SchemaFieldOrderEditor: мы читаем текущий
 * json_schema, мутируем properties (+ propertyOrder) и пишем новую ревизию схемы.
 * Новая типизированная колонка в rd_data появляется при следующем provision/DRAFT'е.
 */
export function SchemaAttributeEditor({ codesetId, jsonSchema, canEdit }: Props) {
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();

  const attributes = useMemo(() => readAttributes(jsonSchema), [jsonSchema]);

  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ name: string; type: LogicalType; enumCsv?: string }>();
  const selectedType = Form.useWatch("type", form);

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: qk.codesets.schema(codesetId) });
    queryClient.invalidateQueries({ queryKey: qk.codesets.one(codesetId) });
  };

  const onError = (e: unknown) => {
    const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
    message.error(msg);
  };

  // PUT новой ревизии схемы. Сохраняем все прочие ключи схемы как есть, меняем только
  // properties (и держим propertyOrder синхронным, чтобы экспорт видел поле).
  const writeSchema = (
    nextProps: Record<string, unknown>,
    nextOrder: string[],
  ): Promise<unknown> =>
    apiMutations.putSchemaRevision(codesetId, {
      ...jsonSchema,
      type: typeof jsonSchema.type === "string" ? jsonSchema.type : "object",
      properties: nextProps,
      propertyOrder: nextOrder,
    });

  const currentProps = (): Record<string, unknown> => {
    const p = jsonSchema.properties;
    return p && typeof p === "object" && !Array.isArray(p)
      ? { ...(p as Record<string, unknown>) }
      : {};
  };

  const currentOrder = (): string[] =>
    Array.isArray(jsonSchema.propertyOrder)
      ? (jsonSchema.propertyOrder as unknown[]).filter(
          (x): x is string => typeof x === "string",
        )
      : [];

  const addAttr = useMutation({
    mutationFn: async (vals: { name: string; type: LogicalType; enumCsv?: string }) => {
      const enumValues = (vals.enumCsv ?? "")
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean);
      const props = currentProps();
      props[vals.name] = buildProperty(vals.type, enumValues);
      const order = currentOrder();
      if (!order.includes(vals.name)) order.push(vals.name);
      await writeSchema(props, order);
      return vals.name;
    },
    onSuccess: (name) => {
      message.success(t("schemaAttr.added", { name }));
      setOpen(false);
      form.resetFields();
      invalidate();
    },
    onError,
  });

  const removeAttr = useMutation({
    mutationFn: async (name: string) => {
      const props = currentProps();
      delete props[name];
      const order = currentOrder().filter((n) => n !== name);
      await writeSchema(props, order);
      return name;
    },
    onSuccess: (name) => {
      message.success(t("schemaAttr.deleted", { name }));
      invalidate();
    },
    onError,
  });

  const existingNames = useMemo(
    () => new Set(attributes.map((a) => a.name)),
    [attributes],
  );

  return (
    <Space direction="vertical" style={{ width: "100%" }} size={8}>
      <Space wrap>
        <Typography.Text strong>{t("schemaAttr.title")}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t("schemaAttr.hint")}
        </Typography.Text>
      </Space>

      {attributes.length === 0 ? (
        <Typography.Text type="secondary">{t("schemaAttr.noFields")}</Typography.Text>
      ) : (
        <List
          size="small"
          bordered
          dataSource={attributes}
          style={{ maxWidth: 520, background: "#fff" }}
          renderItem={(a) => (
            <List.Item
              actions={
                canEdit
                  ? [
                      <Popconfirm
                        key="del"
                        title={t("schemaAttr.deleteConfirm", { name: a.name })}
                        description={t("schemaAttr.deleteConfirmHint")}
                        okButtonProps={{ danger: true }}
                        onConfirm={() => removeAttr.mutate(a.name)}
                      >
                        <Button
                          size="small"
                          type="text"
                          danger
                          icon={<DeleteOutlined />}
                          loading={removeAttr.isPending}
                        />
                      </Popconfirm>,
                    ]
                  : undefined
              }
            >
              <Space wrap>
                <Typography.Text code>{a.name}</Typography.Text>
                <Tag color="blue">{t(`schemaAttr.type.${typeLabelKey(a.prop)}`)}</Tag>
                {a.enumValues && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {a.enumValues.join(", ")}
                  </Typography.Text>
                )}
              </Space>
            </List.Item>
          )}
        />
      )}

      {canEdit && (
        <Button
          type="dashed"
          size="small"
          icon={<PlusOutlined />}
          onClick={() => setOpen(true)}
        >
          {t("schemaAttr.addButton")}
        </Button>
      )}

      <Modal
        title={t("schemaAttr.modalTitle")}
        open={open}
        destroyOnClose
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={addAttr.isPending}
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ type: "number" }}
          onFinish={(vals) => addAttr.mutate(vals)}
          preserve={false}
        >
          <Form.Item
            name="name"
            label={t("schemaAttr.fieldName")}
            extra={t("schemaAttr.fieldNameHint")}
            rules={[
              { required: true },
              {
                pattern: /^[a-z][a-z0-9_]{0,62}$/,
                message: t("schemaAttr.fieldNamePattern"),
              },
              {
                validator: (_, value: string) => {
                  if (!value) return Promise.resolve();
                  if (RESERVED.has(value))
                    return Promise.reject(new Error(t("schemaAttr.reserved")));
                  if (existingNames.has(value))
                    return Promise.reject(new Error(t("schemaAttr.duplicate")));
                  return Promise.resolve();
                },
              },
            ]}
          >
            <Input placeholder="probability" autoComplete="off" />
          </Form.Item>

          <Form.Item name="type" label={t("schemaAttr.fieldType")} rules={[{ required: true }]}>
            <Select
              options={[
                { value: "number", label: t("schemaAttr.type.number") },
                { value: "integer", label: t("schemaAttr.type.integer") },
                { value: "string", label: t("schemaAttr.type.string") },
                { value: "boolean", label: t("schemaAttr.type.boolean") },
                { value: "date", label: t("schemaAttr.type.date") },
                { value: "enum", label: t("schemaAttr.type.enum") },
              ]}
            />
          </Form.Item>

          {selectedType === "enum" && (
            <Form.Item
              name="enumCsv"
              label={t("schemaAttr.enumValues")}
              extra={t("schemaAttr.enumHint")}
              rules={[{ required: true }]}
            >
              <Input placeholder="A, B, C" autoComplete="off" />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Space>
  );
}
