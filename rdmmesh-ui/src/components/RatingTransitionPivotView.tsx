import { useMemo, useState } from "react";
import { Empty, Select, Space, Table, Tag, Tooltip, Typography, type TableColumnsType } from "antd";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";

import { api } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import type { CodeItem, KeySpec } from "@/api/types";

/**
 * RatingTransitionPivotView — pivot-вью матрицы миграций рейтингов (E19 Commit 2).
 *
 * Активируется в VersionPage, когда у CodeSet есть тег `kind:transition_matrix`
 * (см. E19 handoff §2.3 — выбор tags вместо отдельной колонки `kind` для Slice C).
 * Ожидаемая форма key_parts: [from_rating, to_rating, horizon].
 *
 * Read-only в Commit 2 — пользователь редактирует через стандартный Long-вью
 * (ItemsTable). Inline-edit ячеек pivot'а — в Commit 3 или V1+.
 *
 * TODO(E19 V1+): порядок рейтингов сейчас захардкожен KNOWN_RATING_ORDER. В
 * проде брать из cross-codeset CodeSet'а `rating_scale` (атрибут `order`, см.
 * seed-credit-risk.sh). Для Slice C достаточно: незнакомые рейтинги добавляются
 * после известных по алфавиту.
 */

// Известный порядок рейтингов в seed (AAA лучший, D — absorbing/дефолт).
// Незнакомые значения → в конец по алфавиту.
const KNOWN_RATING_ORDER = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"] as const;

// Стандартная банковская шкала DPD (Days Past Due). Используется для
// delinquency-матриц — порядок «по тяжести просрочки», closed/current — терминал.
const KNOWN_DPD_ORDER = [
  "current",
  "0d",
  "0",
  "1-30d",
  "1-30",
  "30d",
  "31-60d",
  "31-90d",
  "60d",
  "90d",
  "90+d",
  "91+d",
  "default",
  "closed",
] as const;

// Если в большинстве строк сумма далека от 1 — это не стохастическая матрица
// (например delinquency flow-rates). В этом случае Σ-колонка не должна красниться:
// сумма ≠ 1 для таких матриц — норма, не ошибка.
function isStochasticMatrix(sums: number[]): boolean {
  if (sums.length === 0) return true;
  const eps = 1e-3;
  const okCount = sums.filter((s) => Math.abs(s - 1) <= eps).length;
  return okCount >= Math.max(1, Math.floor(sums.length / 2));
}

interface Props {
  items: CodeItem[];
  // E20 — нужен чтобы достать label_codeset_ref для осей from/to.
  // Если null — view работает как раньше (только коды в заголовках).
  keySpec?: KeySpec | null;
}

export function RatingTransitionPivotView({ items, keySpec }: Props) {
  const { t } = useTranslation();

  // E20 — единственный ref-словарь для осей from/to (parts[0]/parts[1]).
  // Берём из parts[0]; если parts[1] ссылается на другой — игнорируем
  // (для DPD-кейса оба = один словарь, что и нужно в 90% случаев).
  const refCodesetId =
    keySpec?.parts?.[0]?.label_codeset_ref?.codeset_id ?? null;
  const dictLabels = useDictLabels(refCodesetId);

  // 1. Discover horizons (key_parts[2]) — для Select'а
  const horizons = useMemo(() => {
    const set = new Set<string>();
    for (const it of items) {
      if (it.key_parts && it.key_parts.length >= 3 && it.key_parts[2]) {
        set.add(it.key_parts[2]);
      }
    }
    // Сортируем горизонты по «логической длительности». Для неизвестных
    // значений — numeric fallback ('10' < '2' лексикографически — баг, поэтому
    // парсим число): сначала по числу, иначе alpha.
    const order = ["1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y"];
    return Array.from(set).sort((a, b) => {
      const ia = order.indexOf(a);
      const ib = order.indexOf(b);
      if (ia >= 0 && ib >= 0) return ia - ib;
      if (ia >= 0) return -1;
      if (ib >= 0) return 1;
      const na = parseFloat(a);
      const nb = parseFloat(b);
      if (!isNaN(na) && !isNaN(nb)) return na - nb;
      return a.localeCompare(b);
    });
  }, [items]);

  // Default — первый доступный горизонт (обычно 1Y или 1M).
  const defaultHorizon = horizons[0] ?? null;
  const [horizon, setHorizon] = useState<string | null>(defaultHorizon);
  // Если props.items пересоздан после фетча и поменялся набор горизонтов — обновим.
  if (horizon === null && defaultHorizon !== null) {
    setHorizon(defaultHorizon);
  }

  // 2. Фильтруем по выбранному горизонту.
  const filtered = useMemo(
    () => items.filter((it) => (it.key_parts?.[2] ?? null) === horizon),
    [items, horizon],
  );

  // 3. Discover оси (rows = from, cols = to) из отфильтрованных items.
  const { axis, cellMap } = useMemo(() => {
    const fromSet = new Set<string>();
    const toSet = new Set<string>();
    const map = new Map<string, number>(); // key "from|to" → probability
    for (const it of filtered) {
      if (!it.key_parts || it.key_parts.length < 3) continue;
      const from = it.key_parts[0];
      const to = it.key_parts[1];
      if (!from || !to) continue;
      fromSet.add(from);
      toSet.add(to);
      const p = it.attributes?.probability;
      if (typeof p === "number" && isFinite(p)) {
        map.set(`${from}|${to}`, p);
      }
    }
    // Объединяем from и to — оси должны быть одинаковы (квадратная матрица).
    const all = new Set<string>([...fromSet, ...toSet]);
    const axis = sortRatings(Array.from(all));
    return { axis, cellMap: map };
  }, [filtered]);

  if (horizons.length === 0) {
    return (
      <Empty
        description={t("pivot.noData")}
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  // 4. Строки таблицы — по from_rating.
  type Row = { from: string; sum: number; isAbsorbing: boolean };
  const rows: Row[] = axis.map((from) => {
    let sum = 0;
    for (const to of axis) {
      const p = cellMap.get(`${from}|${to}`);
      if (typeof p === "number") sum += p;
    }
    // Эвристика absorbing: D→D=1 и все остальные D→x=0.
    const selfP = cellMap.get(`${from}|${from}`) ?? 0;
    const allOthers = axis
      .filter((to) => to !== from)
      .every((to) => (cellMap.get(`${from}|${to}`) ?? 0) === 0);
    const isAbsorbing = selfP === 1 && allOthers;
    return { from, sum, isAbsorbing };
  });

  // Если матрица не стохастическая (delinquency / flow-rates) — не красним Σ.
  const stochastic = isStochasticMatrix(rows.map((r) => r.sum));

  const columns: TableColumnsType<Row> = [
    {
      title: t("pivot.fromHeader"),
      dataIndex: "from",
      key: "from",
      fixed: "left",
      width: dictLabels.size > 0 ? 160 : 90,
      render: (from: string, row) => (
        <Space size={4}>
          <strong>{from}</strong>
          {renderLabelSuffix(dictLabels, from)}
          {row.isAbsorbing && (
            <Tooltip title={t("pivot.absorbingHint")}>
              <Tag color="default">{t("pivot.absorbingShort")}</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    // Колонки = to_rating (с подпиской labels при наличии ref-словаря).
    ...axis.map((to) => {
      const label = dictLabels.get(to) ?? dictLabels.get(normalizeNumericCode(to));
      return {
        title: (
          <Space size={4} direction="vertical" style={{ textAlign: "right", lineHeight: 1.1 }}>
            <code>{to}</code>
            {label && (
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {label}
              </Typography.Text>
            )}
          </Space>
        ),
        key: `to_${to}`,
        align: "right" as const,
        width: label ? 110 : 86,
        render: (_v: unknown, row: Row) => {
          const p = cellMap.get(`${row.from}|${to}`);
          if (p === undefined) return <Typography.Text type="secondary">—</Typography.Text>;
          return <span style={diagStyle(row.from === to, p)}>{fmt(p)}</span>;
        },
      };
    }),
    {
      title: <Tooltip title={t("pivot.sumHint")}>Σ</Tooltip>,
      dataIndex: "sum",
      key: "sum",
      align: "right",
      width: 90,
      render: (sum: number) => (
        <strong style={{ color: stochastic ? stochasticColor(sum) : "inherit" }}>
          {fmt(sum)}
        </strong>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Typography.Text strong>{t("pivot.horizon")}:</Typography.Text>
        <Select<string>
          value={horizon ?? undefined}
          onChange={(v) => setHorizon(v)}
          style={{ minWidth: 90 }}
          options={horizons.map((h) => ({ label: h, value: h }))}
        />
        <Typography.Text type="secondary" style={{ marginLeft: 12 }}>
          {t("pivot.cellLegend")}
        </Typography.Text>
      </Space>
      <Table<Row>
        rowKey="from"
        size="small"
        bordered
        pagination={false}
        columns={columns}
        dataSource={rows}
        scroll={{ x: "max-content" }}
      />
    </div>
  );
}

// ── helpers ─────────────────────────────────────────────────────────────────

function fmt(p: number): string {
  return p.toFixed(3);
}

function diagStyle(isDiagonal: boolean, p: number): React.CSSProperties {
  const base: React.CSSProperties = {
    fontFamily: "var(--font-mono, monospace)",
  };
  if (isDiagonal) base.fontWeight = 600;
  if (p === 0) base.color = "#bbb";
  return base;
}

// Зелёный — сумма ≈ 1 (стохастична); красный иначе.
function stochasticColor(sum: number): string {
  const eps = 1e-3;
  return Math.abs(sum - 1) <= eps ? "#52c41a" : "#cf1322";
}

// E20 — нормализация «excelовских» числовых кодов: '1.0' / '1.00' → '1'.
// Нужно потому, что Excel при вводе целого в numeric-ячейке хранит double 1.0,
// и fastexcel-reader возвращает текст '1.0'. Словарь обычно сидится с
// целочисленными ключами '1','2',… — без нормализации матч не сработает.
// '1.5' / 'AAA' остаются как есть.
function normalizeNumericCode(s: string): string {
  return /^-?\d+\.0+$/.test(s) ? s.split(".")[0] : s;
}

// Semver compare без зависимостей: '0.10.0' > '0.2.0' (а localeCompare с
// numeric:true тоже работает, но в Safari ведёт себя нестабильно — пишем явно).
function compareSemver(a: string, b: string): number {
  const pa = a.split(".").map((s) => parseInt(s, 10) || 0);
  const pb = b.split(".").map((s) => parseInt(s, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const diff = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

// E20 — резолвер меток из ref-словаря. Цепочка: versions(by-codeset) →
// items(latest PUBLISHED). Раньше пытался читать CodeSet.current_published_version,
// но backend это поле НЕ возвращает (см. CodeSetResource DTO) — поэтому идём
// напрямую через список версий и сами выбираем самую свежую PUBLISHED.
// Каждый шаг React Query кэширует независимо (staleTime 30s).
// Если у dict-CodeSet'а нет PUBLISHED-версии (только DRAFT/IN_REVIEW) —
// возвращаем пустую мапу, view деградирует к «голым» кодам без ошибок.
// Мапа индексируется ОБОИМИ вариантами кода: '1' → label И '1.0' → label, чтобы
// lookup работал независимо от того, как Excel записал число.
function useDictLabels(refCodesetId: string | null): Map<string, string> {
  const versions = useQuery({
    queryKey: qk.versions.byCodeset(refCodesetId ?? "__none"),
    queryFn: () => api.listVersionsByCodeSet(refCodesetId!),
    enabled: !!refCodesetId,
  });
  // Выбираем самую свежую PUBLISHED-версию. Сортируем по semver desc; для
  // 0.10.0 vs 0.2.0 numeric-compare даст корректный порядок.
  const publishedVersionId = useMemo(() => {
    const pub = (versions.data ?? []).filter((v) => v.status === "PUBLISHED");
    pub.sort((a, b) => compareSemver(b.version, a.version));
    return pub[0]?.id ?? null;
  }, [versions.data]);

  const items = useQuery({
    queryKey: qk.versions.items(publishedVersionId ?? "__none", 0, 1000),
    queryFn: () => api.listItems(publishedVersionId!, 0, 1000),
    enabled: !!publishedVersionId,
  });

  return useMemo(() => {
    const map = new Map<string, string>();
    for (const it of items.data?.items ?? []) {
      const code = it.key_parts?.[0];
      if (!code) continue;
      const label = it.label_ru || it.label_en || "";
      if (!label) continue;
      map.set(code, label);
      // Заводим оба варианта: '1.0' тоже резолвится в label словарного '1'.
      const norm = normalizeNumericCode(code);
      if (norm !== code) map.set(norm, label);
      // И наоборот: если словарь сидится как '1.0', матрица с '1' тоже найдёт.
      if (code.match(/^-?\d+$/)) map.set(code + ".0", label);
    }
    return map;
  }, [items.data]);
}

function renderLabelSuffix(dict: Map<string, string>, code: string) {
  const label = dict.get(code) ?? dict.get(normalizeNumericCode(code));
  if (!label) return null;
  return (
    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
      — {label}
    </Typography.Text>
  );
}

function sortRatings(values: string[]): string[] {
  // Сначала пробуем DPD-порядок (delinquency), потом rating-порядок, потом
  // numeric (если все значения числовые — '1' < '2' < … < '10', а не '10' < '2'),
  // потом alpha.
  const norm = (v: string) => v.trim().toLowerCase();
  const dpdIdx = (v: string) => {
    const n = norm(v);
    const idx = (KNOWN_DPD_ORDER as readonly string[]).indexOf(n);
    return idx >= 0 ? idx : -1;
  };
  const ratingIdx = (v: string) => (KNOWN_RATING_ORDER as readonly string[]).indexOf(v);

  const dpdHits = values.filter((v) => dpdIdx(v) >= 0).length;
  const ratingHits = values.filter((v) => ratingIdx(v) >= 0).length;
  const useDpd = dpdHits >= ratingHits && dpdHits > 0;
  // Если ни DPD, ни rating не подходят, проверяем числовой fallback.
  const allNumeric =
    !useDpd &&
    ratingHits === 0 &&
    values.length > 0 &&
    values.every((v) => /^-?\d+(\.\d+)?$/.test(v.trim()));

  const key = useDpd ? dpdIdx : ratingIdx;
  return [...values].sort((a, b) => {
    const ka = key(a);
    const kb = key(b);
    const ea = ka < 0 ? Number.MAX_SAFE_INTEGER : ka;
    const eb = kb < 0 ? Number.MAX_SAFE_INTEGER : kb;
    if (ea !== eb) return ea - eb;
    if (allNumeric) return parseFloat(a) - parseFloat(b);
    return a.localeCompare(b);
  });
}
