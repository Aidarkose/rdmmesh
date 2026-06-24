import { useMemo, useState } from "react";
import {
  Button,
  Card,
  Empty,
  Form,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tag,
  Typography,
  App as AntApp,
} from "antd";
import { DatabaseOutlined, LinkOutlined, PlusOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { api, apiMutations, type ForeignKeyReport } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { useApi } from "@/api/useApi";
import type { CodeSet, CodeSetRef } from "@/api/types";

// Колонки справочника, на которые можно ссылаться/которые можно линковать: имена
// key-part'ов + имена атрибутов из активной JSON Schema.
function columnsOf(cs: CodeSet | null | undefined, jsonSchema?: Record<string, unknown>): string[] {
  const parts = (cs?.key_spec?.parts ?? []).map((p) => p.name);
  const props = jsonSchema?.properties as Record<string, unknown> | undefined;
  const attrs = props ? Object.keys(props) : [];
  return Array.from(new Set([...parts, ...attrs]));
}

// Ссылка на связанный справочник — резолвит имя по id, чтобы пользователь видел
// «человеческое» имя и мог перейти к нему (увидеть справочники рядом).
function RefTarget({ codesetId, column }: { codesetId: string; column?: string }) {
  const cs = useApi(() => api.getCodeSet(codesetId), qk.codesets.one(codesetId));
  const name = cs.data?.display_name ?? cs.data?.name ?? codesetId.slice(0, 8);
  return (
    <Link to={`/codesets/${codesetId}`}>
      <LinkOutlined /> {name}
      {column ? <Typography.Text code>.{column}</Typography.Text> : null}
    </Link>
  );
}

interface AddForm {
  from_column: string;
  to_domain_id: string;
  to_codeset_id: string;
  to_column: string;
}

export function CodeSetReferencesPanel({
  codeset,
  jsonSchema,
  canEdit,
}: {
  codeset: CodeSet;
  jsonSchema?: Record<string, unknown>;
  canEdit: boolean;
}) {
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [report, setReport] = useState<ForeignKeyReport | null>(null);
  const [form] = Form.useForm<AddForm>();

  const refs = useMemo(() => codeset.references ?? [], [codeset.references]);
  const localColumns = useMemo(() => columnsOf(codeset, jsonSchema), [codeset, jsonSchema]);

  const domains = useApi(() => api.listDomains(), qk.domains.all());

  // Целевой домен/справочник в форме — наблюдаем, чтобы подгружать списки каскадом.
  const toDomainId = Form.useWatch("to_domain_id", form);
  const toCodesetId = Form.useWatch("to_codeset_id", form);
  const targetCodesets = useApi(
    () => (toDomainId ? api.listCodeSetsByDomain(toDomainId) : Promise.resolve([])),
    qk.codesets.byDomain(toDomainId ?? "none"),
  );
  // Схема целевого справочника — чтобы предлагать не только key-part'ы, но и атрибуты
  // (FK может указывать на атрибут, напр. r_coef_pd.product_id → ...r_ecl_prdct_sgmnt).
  // listCodeSetsByDomain отдаёт key_spec, но не схему атрибутов — тянем её отдельно.
  const targetSchema = useApi(
    () => (toCodesetId ? api.getActiveSchema(toCodesetId) : Promise.resolve(null)),
    qk.codesets.schema(toCodesetId ?? "none"),
  );
  const targetColumns = useMemo(() => {
    const target = targetCodesets.data?.find((c) => c.id === toCodesetId);
    return columnsOf(target, targetSchema.data?.json_schema);
  }, [targetCodesets.data, toCodesetId, targetSchema.data]);

  const save = useMutation({
    mutationFn: (next: CodeSetRef[]) => apiMutations.setCodeSetReferences(codeset.id, next),
    onSuccess: () => {
      message.success("Связи сохранены");
      queryClient.invalidateQueries({ queryKey: qk.codesets.one(codeset.id) });
      setOpen(false);
      form.resetFields();
    },
    onError: (e: Error) => message.error(e.message),
  });

  // Stage 6 — материализовать column_refs в настоящие Postgres FK (видны в DBeaver).
  const applyFk = useMutation({
    mutationFn: () => apiMutations.applyForeignKeys(codeset.id),
    onSuccess: (rep) => {
      setReport(rep);
      const applied = rep.applied.length;
      const skipped = rep.skipped.length;
      if (applied > 0) message.success(`Создано FK: ${applied}` + (skipped ? `, пропущено: ${skipped}` : ""));
      else message.warning(skipped ? `Создавать нечего, пропущено: ${skipped}` : "Связей для материализации нет");
    },
    onError: (e: Error) => message.error(e.message),
  });

  const onAdd = (v: AddForm) => {
    const dup = refs.some(
      (r) =>
        r.from_column === v.from_column &&
        r.to_codeset_id === v.to_codeset_id &&
        r.to_column === v.to_column,
    );
    if (dup) {
      message.warning("Такая связь уже есть");
      return;
    }
    const next: CodeSetRef[] = [
      ...refs,
      { from_column: v.from_column, to_codeset_id: v.to_codeset_id, to_column: v.to_column },
    ];
    save.mutate(next);
  };

  const onRemove = (ref: CodeSetRef) => {
    const next = refs.filter(
      (r) =>
        !(
          r.from_column === ref.from_column &&
          r.to_codeset_id === ref.to_codeset_id &&
          r.to_column === ref.to_column
        ),
    );
    save.mutate(next);
  };

  return (
    <Card
      title="Связи со справочниками"
      size="small"
      style={{ marginBottom: 16 }}
      extra={
        canEdit ? (
          <Space>
            <Button
              size="small"
              icon={<DatabaseOutlined />}
              loading={applyFk.isPending}
              disabled={refs.length === 0}
              onClick={() => applyFk.mutate()}
            >
              Применить FK в БД
            </Button>
            <Button size="small" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
              Добавить связь
            </Button>
          </Space>
        ) : null
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        FK-связи колонок этого справочника с колонками других справочников (в т.ч. из других
        доменов). Публикуются в OpenMetadata как FOREIGN_KEY — связанные справочники видны рядом
        и по ним можно навигировать.
      </Typography.Paragraph>

      {refs.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Связей пока нет" />
      ) : (
        <List
          dataSource={refs}
          renderItem={(r) => (
            <List.Item
              actions={
                canEdit
                  ? [
                      <Popconfirm
                        key="del"
                        title="Удалить связь?"
                        onConfirm={() => onRemove(r)}
                      >
                        <Button size="small" danger type="link" loading={save.isPending}>
                          Удалить
                        </Button>
                      </Popconfirm>,
                    ]
                  : undefined
              }
            >
              <Space wrap>
                <Tag color="blue">{r.from_column}</Tag>
                <Typography.Text type="secondary">→</Typography.Text>
                <RefTarget codesetId={r.to_codeset_id} column={r.to_column} />
              </Space>
            </List.Item>
          )}
        />
      )}

      <Modal
        title="Новая связь справочников"
        open={open}
        onCancel={() => setOpen(false)}
        destroyOnClose
        onOk={() => form.submit()}
        confirmLoading={save.isPending}
        okText="Сохранить"
        cancelText="Отмена"
      >
        <Form form={form} layout="vertical" onFinish={onAdd}>
          <Form.Item
            name="from_column"
            label="Колонка этого справочника"
            rules={[{ required: true }]}
          >
            <Select
              showSearch
              placeholder="key-part или атрибут"
              options={localColumns.map((c) => ({ value: c, label: c }))}
            />
          </Form.Item>

          <Form.Item name="to_domain_id" label="Домен целевого справочника" rules={[{ required: true }]}>
            <Select
              showSearch
              loading={domains.loading}
              placeholder="домен"
              optionFilterProp="label"
              options={(domains.data ?? []).map((d) => ({
                value: d.id,
                label: d.display_name ?? d.name,
              }))}
              onChange={() => form.setFieldsValue({ to_codeset_id: undefined, to_column: undefined })}
            />
          </Form.Item>

          <Form.Item name="to_codeset_id" label="Целевой справочник" rules={[{ required: true }]}>
            <Select
              showSearch
              disabled={!toDomainId}
              loading={targetCodesets.loading}
              placeholder="справочник"
              optionFilterProp="label"
              options={(targetCodesets.data ?? []).map((c) => ({
                value: c.id,
                label: c.display_name ?? c.name,
              }))}
              onChange={() => form.setFieldsValue({ to_column: undefined })}
            />
          </Form.Item>

          <Form.Item name="to_column" label="Колонка целевого справочника" rules={[{ required: true }]}>
            <Select
              showSearch
              disabled={!toCodesetId}
              loading={targetSchema.loading}
              placeholder="key-part или атрибут цели"
              options={targetColumns.map((c) => ({ value: c, label: c }))}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="Результат: применение FK в БД"
        open={report !== null}
        onCancel={() => setReport(null)}
        footer={<Button onClick={() => setReport(null)}>Закрыть</Button>}
      >
        <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
          FK ставятся между опубликованными таблицами <Typography.Text code>__current</Typography.Text>.
          Связь пропускается, если целевая колонка не уникальна или справочник не опубликован.
        </Typography.Paragraph>

        <Typography.Text strong>Создано ({report?.applied.length ?? 0})</Typography.Text>
        {report && report.applied.length > 0 ? (
          <List
            size="small"
            dataSource={report.applied}
            renderItem={(a) => (
              <List.Item>
                <Space wrap>
                  <Tag color="green">{a.fromColumn}</Tag>
                  <Typography.Text type="secondary">→</Typography.Text>
                  <Typography.Text code>
                    {a.toTable}.{a.toColumn}
                  </Typography.Text>
                </Space>
              </List.Item>
            )}
          />
        ) : (
          <Typography.Paragraph type="secondary">— нет</Typography.Paragraph>
        )}

        <Typography.Text strong>Пропущено ({report?.skipped.length ?? 0})</Typography.Text>
        {report && report.skipped.length > 0 ? (
          <List
            size="small"
            dataSource={report.skipped}
            renderItem={(s) => (
              <List.Item>
                <Space wrap>
                  <Tag color="orange">{s.fromColumn}</Tag>
                  <Typography.Text type="secondary">{s.reason}</Typography.Text>
                </Space>
              </List.Item>
            )}
          />
        ) : (
          <Typography.Paragraph type="secondary">— нет</Typography.Paragraph>
        )}
      </Modal>
    </Card>
  );
}
