import { useEffect, useMemo, useState } from "react";
import { App as AntApp, Button, List, Space, Typography } from "antd";
import { ArrowDownOutlined, ArrowUpOutlined, SaveOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  codesetId: string;
  jsonSchema: Record<string, unknown>;
  // редактирование доступно только Schema Designer / Admin; иначе список read-only.
  canEdit: boolean;
}

/**
 * E24: порядок атрибутов справочника. Поля берутся из `properties` JSON Schema,
 * упорядочиваются по существующему массиву `propertyOrder` (имена не из properties
 * игнорируются), затем дописываются оставшиеся ключи. Сохранение пишет новую ревизию
 * схемы с обновлённым `propertyOrder` — экспорт в Excel разворачивает attr.*-колонки
 * именно в этом порядке.
 */
function orderedFieldNames(schema: Record<string, unknown>): string[] {
  const props = schema.properties;
  const keys =
    props && typeof props === "object" && !Array.isArray(props)
      ? Object.keys(props as Record<string, unknown>)
      : [];
  const declared = Array.isArray(schema.propertyOrder)
    ? (schema.propertyOrder as unknown[]).filter((x): x is string => typeof x === "string")
    : [];
  const seen = new Set<string>();
  const result: string[] = [];
  for (const name of declared) {
    if (keys.includes(name) && !seen.has(name)) {
      result.push(name);
      seen.add(name);
    }
  }
  for (const k of keys) {
    if (!seen.has(k)) {
      result.push(k);
      seen.add(k);
    }
  }
  return result;
}

export function SchemaFieldOrderEditor({ codesetId, jsonSchema, canEdit }: Props) {
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();

  const initial = useMemo(() => orderedFieldNames(jsonSchema), [jsonSchema]);
  const [order, setOrder] = useState<string[]>(initial);

  // сбросить локальный порядок при перезагрузке схемы (после сохранения/смены codeset).
  useEffect(() => {
    setOrder(initial);
  }, [initial]);

  const dirty = useMemo(
    () => order.length === initial.length && order.some((n, i) => n !== initial[i]),
    [order, initial],
  );

  const save = useMutation({
    mutationFn: () =>
      apiMutations.putSchemaRevision(codesetId, { ...jsonSchema, propertyOrder: order }),
    onSuccess: () => {
      message.success(t("schemaOrder.saved"));
      queryClient.invalidateQueries({ queryKey: qk.codesets.schema(codesetId) });
      queryClient.invalidateQueries({ queryKey: qk.codesets.one(codesetId) });
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const move = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= order.length) return;
    const next = [...order];
    [next[i], next[j]] = [next[j], next[i]];
    setOrder(next);
  };

  if (initial.length === 0) {
    return <Typography.Text type="secondary">{t("schemaOrder.noFields")}</Typography.Text>;
  }

  return (
    <Space direction="vertical" style={{ width: "100%" }} size={8}>
      <Space wrap>
        <Typography.Text strong>{t("schemaOrder.title")}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t("schemaOrder.hint")}
        </Typography.Text>
      </Space>
      <List
        size="small"
        bordered
        dataSource={order}
        style={{ maxWidth: 420, background: "#fff" }}
        renderItem={(name, i) => (
          <List.Item
            actions={
              canEdit
                ? [
                    <Button
                      key="up"
                      size="small"
                      type="text"
                      icon={<ArrowUpOutlined />}
                      disabled={i === 0 || save.isPending}
                      onClick={() => move(i, -1)}
                    />,
                    <Button
                      key="down"
                      size="small"
                      type="text"
                      icon={<ArrowDownOutlined />}
                      disabled={i === order.length - 1 || save.isPending}
                      onClick={() => move(i, 1)}
                    />,
                  ]
                : undefined
            }
          >
            <Space>
              <Typography.Text type="secondary">{i + 1}.</Typography.Text>
              <Typography.Text code>{name}</Typography.Text>
            </Space>
          </List.Item>
        )}
      />
      {canEdit && (
        <Space>
          <Button
            type="primary"
            size="small"
            icon={<SaveOutlined />}
            disabled={!dirty}
            loading={save.isPending}
            onClick={() => save.mutate()}
          >
            {t("schemaOrder.save")}
          </Button>
          {dirty && (
            <Button size="small" disabled={save.isPending} onClick={() => setOrder(initial)}>
              {t("schemaOrder.reset")}
            </Button>
          )}
        </Space>
      )}
    </Space>
  );
}
