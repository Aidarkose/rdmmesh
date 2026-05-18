import { apiFetch, apiFetchRaw } from "./client";
import type {
  ApprovalTask,
  AuditChainVerifyResult,
  AuditPage,
  AuthMe,
  BulkImportResult,
  CodeItem,
  CodeSet,
  CodeSetSchemaDto,
  CodeSetVersion,
  DistributionItemsPage,
  Domain,
  ItemsPage,
  Lang,
  Subscription,
  SubscriptionFilter,
  VerifyResponse,
  VersionDiffResponse,
  VersionStatus,
  WorkflowTransitionEvent,
} from "./types";

// ─── Read-side ────────────────────────────────────────────────────────────────

export const api = {
  authMe: () => apiFetch<AuthMe>("/auth/me"),

  // Catalog
  listDomains: () => apiFetch<Domain[]>("/domains"),
  getDomain: (id: string) => apiFetch<Domain>(`/domains/${id}`),
  listCodeSetsByDomain: (domainId: string) =>
    apiFetch<CodeSet[]>(`/codesets/by-domain/${domainId}`),
  getCodeSet: (id: string) => apiFetch<CodeSet>(`/codesets/${id}`),
  getActiveSchema: (codesetId: string) =>
    apiFetch<CodeSetSchemaDto>(`/codesets/${codesetId}/schema`),
  getSchemaHistory: (codesetId: string) =>
    apiFetch<CodeSetSchemaDto[]>(`/codesets/${codesetId}/schema/history`),

  // Authoring (read)
  listVersionsByCodeSet: (codesetId: string) =>
    apiFetch<CodeSetVersion[]>(`/versions/by-codeset/${codesetId}`),
  getVersion: (id: string) => apiFetch<CodeSetVersion>(`/versions/${id}`),
  listItems: (versionId: string, page = 0, size = 100) =>
    apiFetch<ItemsPage>(`/versions/${versionId}/items?page=${page}&size=${size}`),
  diffVersions: (toVersionId: string, fromVersionId: string) =>
    apiFetch<VersionDiffResponse>(
      `/versions/${toVersionId}/diff?from=${encodeURIComponent(fromVersionId)}`,
    ),

  // Workflow
  transitionHistory: (versionId: string) =>
    apiFetch<WorkflowTransitionEvent[]>(`/versions/${versionId}/history`),
  myTasks: () => apiFetch<ApprovalTask[]>("/tasks/my"),

  // Publishing
  verifyVersion: (versionId: string) =>
    apiFetch<VerifyResponse>(`/versions/${versionId}/verify`),

  // Distribution (E8) — consumer-side read с bitemporal-фильтрами.
  // `domain`/`codeset` — qualified_name (lower snake_case), не UUID.
  distributionItems: (
    domain: string,
    codeset: string,
    opts: DistributionQuery = {},
  ) => {
    const params = new URLSearchParams();
    if (opts.version) params.set("version", opts.version);
    if (opts.asOf) params.set("as_of", opts.asOf);
    if (opts.knowledgeAsOf) params.set("knowledge_as_of", opts.knowledgeAsOf);
    if (opts.lang) params.set("lang", opts.lang);
    params.set("page", String(opts.page ?? 1));
    params.set("size", String(opts.size ?? 1000));
    return apiFetch<DistributionItemsPage>(
      `/rdm/${encodeURIComponent(domain)}/${encodeURIComponent(codeset)}/items?${params.toString()}`,
    );
  },

  // E15 — distribution bulk-export (xlsx/csv/json). Тот же resolve версии,
  // что у distributionItems (version/as_of/knowledge_as_of/lang). xlsx/csv
  // приходят с Content-Disposition: attachment; json — телом без него
  // (fallback-имя строим на клиенте). Качаем как blob через apiFetchRaw.
  downloadDistributionExport: async (
    domain: string,
    codeset: string,
    format: "xlsx" | "csv" | "json",
    opts: DistributionQuery = {},
  ): Promise<void> => {
    const params = new URLSearchParams();
    params.set("format", format);
    if (opts.version) params.set("version", opts.version);
    if (opts.asOf) params.set("as_of", opts.asOf);
    if (opts.knowledgeAsOf) params.set("knowledge_as_of", opts.knowledgeAsOf);
    if (opts.lang) params.set("lang", opts.lang);

    const res = await apiFetchRaw(
      `/rdm/${encodeURIComponent(domain)}/${encodeURIComponent(codeset)}/export?${params.toString()}`,
    );
    const blob = await res.blob();
    const filename =
      parseFilenameFromContentDisposition(res.headers.get("content-disposition")) ??
      `${domain}_${codeset}-${nowStamp()}.${format}`;
    triggerBlobDownload(blob, filename);
  },

  // Subscriptions (E9, RDM_ADMIN)
  listSubscriptions: () => apiFetch<Subscription[]>("/subscriptions"),
  getSubscription: (id: string) => apiFetch<Subscription>(`/subscriptions/${id}`),

  // Audit viewer (E11.2d, RDM_ADMIN). Все фильтры опциональны.
  listAuditEntries: (
    page: number,
    size: number,
    filters: AuditQuery = {},
  ) => {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("size", String(size));
    if (filters.eventType) params.set("event_type", filters.eventType);
    if (filters.aggregateType) params.set("aggregate_type", filters.aggregateType);
    if (filters.aggregateId) params.set("aggregate_id", filters.aggregateId);
    if (filters.actor) params.set("actor", filters.actor);
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.q) params.set("q", filters.q);
    return apiFetch<AuditPage>(`/audit?${params.toString()}`);
  },

  // E14 round 1: проверка целостности hash-chain (RDM_ADMIN). Оба параметра
  // опциональны — backend default'ит на min(id)..max(id), т.е. полная цепочка.
  verifyAuditChain: (fromId?: number | null, toId?: number | null) => {
    const params = new URLSearchParams();
    if (fromId != null) params.set("from", String(fromId));
    if (toId != null) params.set("to", String(toId));
    const query = params.toString();
    return apiFetch<AuditChainVerifyResult>(
      query ? `/audit/verify-chain?${query}` : "/audit/verify-chain",
    );
  },

  // E14 round 4: streaming export audit-журнала для compliance-аудитора.
  // RDM_ADMIN ∨ RDM_AUDITOR. Использует apiFetchRaw — backend отдаёт
  // Content-Disposition: attachment, мы качаем как blob и сохраняем через
  // synthetic <a download>. Filename берётся из Content-Disposition либо
  // fallback'ом строится на стороне клиента.
  downloadAuditExport: async (
    filters: AuditQuery,
    format: "csv" | "ndjson",
  ): Promise<void> => {
    const params = new URLSearchParams();
    params.set("format", format);
    if (filters.eventType) params.set("event_type", filters.eventType);
    if (filters.aggregateType) params.set("aggregate_type", filters.aggregateType);
    if (filters.aggregateId) params.set("aggregate_id", filters.aggregateId);
    if (filters.actor) params.set("actor", filters.actor);
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.q) params.set("q", filters.q);

    const res = await apiFetchRaw(`/audit/export?${params.toString()}`);
    const blob = await res.blob();
    const filename = parseFilenameFromContentDisposition(
      res.headers.get("content-disposition"),
    ) ?? `audit-${nowStamp()}.${format}`;

    triggerBlobDownload(blob, filename);
  },
};

// Сохранить blob как файл через synthetic <a download>. Используется
// audit-export'ом и distribution-export'ом (E15).
function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
}

// Парсер RFC 6266: `attachment; filename="audit-20260512-153045.csv"` →
// `"audit-20260512-153045.csv"`. Игнорирует `filename*=UTF-8''...` — backend
// сейчас не использует encoded form.
function parseFilenameFromContentDisposition(header: string | null): string | null {
  if (!header) return null;
  const m = /filename="([^"]+)"/i.exec(header);
  return m ? m[1] : null;
}

function nowStamp(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}-${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())}`;
}

// Distribution-side фильтры. Все опциональны — пустой объект даёт latest published.
export interface DistributionQuery {
  version?: string | null; // "published" | "<semver>"
  asOf?: string | null; // ISO date YYYY-MM-DD — effective time
  knowledgeAsOf?: string | null; // ISO instant — system time (bitemporal)
  lang?: Lang | null;
  page?: number; // 1-based на distribution-стороне (см. E8)
  size?: number;
}

export interface AuditQuery {
  eventType?: string | null;
  aggregateType?: string | null;
  aggregateId?: string | null;
  actor?: string | null;
  from?: string | null;
  to?: string | null;
  q?: string | null;
}

// ─── Mutations ────────────────────────────────────────────────────────────────

// POST /versions/by-codeset/{codesetId}
export interface CreateDraftRequest {
  version?: string;
  initial_items?: CodeItem[];
}

export const apiMutations = {
  // E11.2a — DRAFT lifecycle + workflow transitions
  createDraft: (codesetId: string, body: CreateDraftRequest = {}) =>
    apiFetch<CodeSetVersion>(`/versions/by-codeset/${codesetId}`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  deleteDraft: (versionId: string) =>
    apiFetch<void>(`/versions/${versionId}`, { method: "DELETE" }),

  createItem: (versionId: string, item: NewItemBody) =>
    apiFetch<CodeItem>(`/versions/${versionId}/items`, {
      method: "POST",
      body: JSON.stringify(item),
    }),

  transition: (versionId: string, body: TransitionRequest) =>
    apiFetch<WorkflowTransitionEvent>(`/versions/${versionId}/transitions`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // E11.2b — items editor
  patchItem: (versionId: string, itemId: string, patch: ItemPatchBody) =>
    apiFetch<CodeItem>(`/versions/${versionId}/items/${itemId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),

  deleteItem: (versionId: string, itemId: string) =>
    apiFetch<void>(`/versions/${versionId}/items/${itemId}`, { method: "DELETE" }),

  // E11.2b — bulk import
  bulkJson: (versionId: string, rows: NewItemBody[]) =>
    apiFetch<BulkImportResult>(`/versions/${versionId}/items/bulk`, {
      method: "POST",
      body: JSON.stringify(rows),
    }),

  // CSV — голый text/csv body, без оборачивания в JSON.
  bulkCsv: (versionId: string, csv: string) =>
    apiFetch<BulkImportResult>(`/versions/${versionId}/items/bulk-csv`, {
      method: "POST",
      headers: { "Content-Type": "text/csv" },
      body: csv,
    }),

  // E15 — XLSX bulk import. Бинарный body (File/Blob), MIME ровно как
  // @Consumes на backend'е. apiFetch не трогает Content-Type для не-string
  // body, поэтому ставим явно.
  bulkXlsx: (versionId: string, file: Blob) =>
    apiFetch<BulkImportResult>(`/versions/${versionId}/items/bulk-xlsx`, {
      method: "POST",
      headers: {
        "Content-Type":
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      },
      body: file,
    }),

  // E11.2c — Subscriptions admin
  createSubscription: (body: SubscriptionCreateRequest) =>
    apiFetch<Subscription>("/subscriptions", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // soft-delete (active=false) — backend никогда не удаляет физически
  deleteSubscription: (id: string) =>
    apiFetch<void>(`/subscriptions/${id}`, { method: "DELETE" }),

  // E13 round 3 — disaster-recovery: пересобрать closure-table версии.
  // Под @RolesAllowed("RDM_ADMIN") на backend.
  rebuildClosure: (versionId: string) =>
    apiFetch<ClosureRebuildResult>(`/versions/${versionId}/closure/rebuild`, {
      method: "POST",
    }),
};

// Возвращается из POST /versions/{id}/closure/rebuild (Java record, camelCase).
export interface ClosureRebuildResult {
  versionId: string;
  removed: number;
  inserted: number;
  total: number;
}

export interface SubscriptionCreateRequest {
  url: string;
  secret_id: string;
  filter?: SubscriptionFilter | null;
  active?: boolean | null;
}

export interface NewItemBody {
  key_parts: unknown[];
  parent_key?: unknown[] | null;
  label_ru?: string | null;
  label_en?: string | null;
  description_ru?: string | null;
  description_en?: string | null;
  attributes?: Record<string, unknown> | null;
  order_index?: number | null;
  status?: string | null;
  effective_from?: string | null;
  effective_to?: string | null;
}

// expected_row_version обязателен на backend'е (CodeItemResource.java:306) — иначе 400.
export interface ItemPatchBody {
  expected_row_version: number;
  parent_key?: unknown[] | null;
  parent_ref?: Record<string, unknown> | null;
  label_ru?: string | null;
  label_en?: string | null;
  description_ru?: string | null;
  description_en?: string | null;
  attributes?: Record<string, unknown> | null;
  order_index?: number | null;
  status?: string | null;
  effective_from?: string | null;
  effective_to?: string | null;
}

export interface TransitionRequest {
  to: VersionStatus;
  comment?: string | null;
  expected_status?: VersionStatus | null;
}
