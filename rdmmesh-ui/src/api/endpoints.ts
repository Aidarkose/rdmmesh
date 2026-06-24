import { apiFetch, apiFetchRaw } from "./client";
import type {
  ApprovalTask,
  Approver,
  AuditChainVerifyResult,
  AuditPage,
  AuthMe,
  BulkImportResult,
  CodeItem,
  CodeSet,
  CodeSetRef,
  CodeSetSchemaDto,
  CodeSetVersion,
  DirectoryRole,
  DistributionItemsPage,
  Domain,
  ItemsPage,
  Lang,
  RelationalSyncStatus,
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

  // Stage 7 (A) — статус синхронизации rd_data после публикации версии.
  relationalSyncStatus: (codesetId: string, versionId: string) =>
    apiFetch<RelationalSyncStatus>(
      `/relational/codesets/${codesetId}/sync-status?version_id=${encodeURIComponent(versionId)}`,
    ),

  // Workflow
  transitionHistory: (versionId: string) =>
    apiFetch<WorkflowTransitionEvent[]>(`/versions/${versionId}/history`),
  myTasks: () => apiFetch<ApprovalTask[]>("/tasks/my"),

  // E17 / BR-21: кандидаты-согласующие домена из справочника ролей домена
  // (для submit-диалога). role опционален: STEWARD | BUSINESS_OWNER.
  listApprovers: (domainId: string, role?: DirectoryRole) =>
    apiFetch<Approver[]>(
      `/domains/${domainId}/approvers${role ? `?role=${role}` : ""}`,
    ),

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

// POST /codesets/by-domain/{domainId} — создание справочника (Schema Designer /
// Author / Admin). key_spec/initial_schema опциональны: backend по умолчанию
// делает одиночный строковый ключ "code" и пустую схему.
export interface CreateCodeSetRequest {
  name: string;
  display_name?: string | null;
  description?: string | null;
  hierarchy_mode?: "NONE" | "INTRA_CODESET" | "CROSS_CODESET" | null;
  key_spec?: Record<string, unknown> | null;
  initial_schema?: Record<string, unknown> | null;
}

export const apiMutations = {
  // E18 — создание справочника в домене (catalog POST /codesets/by-domain/{id}).
  createCodeSet: (domainId: string, body: CreateCodeSetRequest) =>
    apiFetch<CodeSet>(`/codesets/by-domain/${domainId}`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // E24 — новая ревизия CodeSetSchema (catalog PUT /codesets/{id}/schema). Используется
  // редактором порядка полей: тело { json_schema } по контракту SchemaRevisionRequest.
  putSchemaRevision: (codesetId: string, jsonSchema: Record<string, unknown>) =>
    apiFetch<CodeSetSchemaDto>(`/codesets/${codesetId}/schema`, {
      method: "PUT",
      body: JSON.stringify({ json_schema: jsonSchema }),
    }),

  // Cross-codeset FK-связи (catalog PUT /codesets/{id}/references). Полная замена
  // набора: пустой массив очищает связи. Связи публикуются в OpenMetadata как FOREIGN_KEY.
  setCodeSetReferences: (codesetId: string, references: CodeSetRef[]) =>
    apiFetch<CodeSet>(`/codesets/${codesetId}/references`, {
      method: "PUT",
      body: JSON.stringify({ references }),
    }),

  // Материализация column_refs в настоящие Postgres FK между __current-таблицами
  // (Stage 6 / applyForeignKeys). Тела нет — связи читаются из column_refs. Отчёт:
  // applied (создано) + skipped (пропущено, с причиной). Defensive: FK только если
  // целевая колонка — единственная PK-колонка целевого __current.
  applyForeignKeys: (codesetId: string) =>
    apiFetch<ForeignKeyReport>(`/relational/codesets/${codesetId}/foreign-keys`, {
      method: "POST",
    }),

  // E11.2a — DRAFT lifecycle + workflow transitions
  createDraft: (codesetId: string, body: CreateDraftRequest = {}) =>
    apiFetch<CodeSetVersion>(`/versions/by-codeset/${codesetId}`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  deleteDraft: (versionId: string) =>
    apiFetch<void>(`/versions/${versionId}`, { method: "DELETE" }),

  // Stage 7 (B) — повтор авто-публикации после фикса данных (RDM_ADMIN).
  // Нужен, когда публикация была отклонена пред-проверкой (BLOCKED).
  retryPublish: (versionId: string) =>
    apiFetch<{ outcome: string }>(`/versions/${versionId}/publish-retry`, {
      method: "POST",
    }),

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

  // E21 — bulk clear: удалить все items DRAFT-версии. confirm=clear-all обязателен.
  clearAllItems: (versionId: string) =>
    apiFetch<{ deleted: number }>(
      `/versions/${versionId}/items?confirm=clear-all`,
      { method: "DELETE" },
    ),

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

  // E19 Commit 3 — pivot-режим XLSX-импорта для матриц миграций. Тот же
  // endpoint /items/bulk-xlsx, но с query-параметрами ?layout=pivot&horizon=...
  // &row_residual_policy=... Backend раскладывает квадратную матрицу в triples
  // и (опционально) дописывает absorbing-колонку из невязки.
  bulkXlsxPivot: (
    versionId: string,
    file: Blob,
    opts: {
      horizon: string;
      rowResidualPolicy: "implicit_default" | "strict" | "free";
    },
  ) => {
    const qs = new URLSearchParams({
      layout: "pivot",
      horizon: opts.horizon,
      row_residual_policy: opts.rowResidualPolicy,
    }).toString();
    return apiFetch<BulkImportResult>(
      `/versions/${versionId}/items/bulk-xlsx?${qs}`,
      {
        method: "POST",
        headers: {
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        },
        body: file,
      },
    );
  },

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

// ─── E18 (ADR-0011) — Admin domain CRUD, ownership, codeset rename/delete,
// user search, resolution tasks. Все эндпоинты под @RolesAllowed("RDM_ADMIN") ─

export interface AdminDomainView {
  id: string;
  om_domain_id: string | null;
  name: string;
  display_name: string | null;
  description: string | null;
  label_ru: string | null;
  label_en: string | null;
  tags: string[];
  master: "OM" | "RDM" | "LINKED";
  local_overrides: string;
  external_refs: string;
  last_om_sync_at: string | null;
  deleted_in_om_at: string | null;
  active_codeset_count: number;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
}

export interface AdminOwnershipView {
  id: string;
  asset_id: string;
  asset_type: "DOMAIN" | "CODESET";
  om_user_id: string;
  role: "OWNER" | "STEWARD" | "EXPERT" | "APPROVER";
  origin: "OM" | "RDM";
  pinned_local: boolean;
  is_provisional: boolean;
  assigned_at: string;
  assigned_by_user_id: string | null;
}

export interface AdminUserView {
  om_user_id: string;
  username: string;
  display_name: string | null;
  email: string | null;
}

export interface AdminTaskView {
  id: string;
  task_type: "DOMAIN_LINKAGE" | "DOMAIN_DELETED_IN_OM" | "OWNERSHIP_OM_REMOVAL_CONFLICT";
  source_event_id: string;
  related_domain_id: string | null;
  payload: string;
  status: "PENDING" | "RESOLVED";
  created_at: string;
}

export interface CreateDomainRequest {
  name: string;
  om_domain_id?: string | null;
  display_name?: string | null;
  description?: string | null;
  label_ru?: string | null;
  label_en?: string | null;
  tags?: string[] | null;
}

export interface PatchDomainRequest {
  display_name?: string | null;
  description?: string | null;
  label_ru?: string | null;
  label_en?: string | null;
  tags?: string[] | null;
}

export interface AssignOwnershipRequest {
  om_user_id: string;
  role: "OWNER" | "STEWARD" | "EXPERT" | "APPROVER";
  pinned_local?: boolean;
}

export const adminApi = {
  // Domain CRUD
  listDomains: () => apiFetch<AdminDomainView[]>("/admin/domains"),
  getDomain: (id: string) => apiFetch<AdminDomainView>(`/admin/domains/${id}`),

  // Ownership
  listDomainOwnership: (id: string) =>
    apiFetch<AdminOwnershipView[]>(`/admin/domains/${id}/ownership`),
  listCodesetOwnership: (id: string) =>
    apiFetch<AdminOwnershipView[]>(`/admin/codesets/${id}/ownership`),

  // User search
  searchUsers: (q: string, limit = 20) =>
    apiFetch<AdminUserView[]>(
      `/admin/users/search?q=${encodeURIComponent(q)}&limit=${limit}`,
    ),

  // Согласующие домена (directory) — адресно по domain_id (RDM_ADMIN).
  listDomainApprovers: (domainId: string) =>
    apiFetch<Approver[]>(`/admin/domains/${domainId}/approvers`),

  // Tasks
  myAdminTasks: () => apiFetch<AdminTaskView[]>("/admin/tasks/my"),

  // E22 — admin queue заявок на удаление CodeSet'а.
  listDeletionRequests: (status: DeletionRequestStatus = "PENDING") =>
    apiFetch<DeletionRequestView[]>(
      `/admin/deletion-requests?status=${encodeURIComponent(status)}`,
    ),
};

// E22 — author-side: список моих заявок (любой статус).
export const deletionRequestsApi = {
  listMy: () => apiFetch<DeletionRequestView[]>("/deletion-requests/my"),
};

export const adminMutations = {
  createDomain: (body: CreateDomainRequest) =>
    apiFetch<AdminDomainView>("/admin/domains", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  patchDomain: (id: string, body: PatchDomainRequest) =>
    apiFetch<AdminDomainView>(`/admin/domains/${id}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),
  renameDomain: (id: string, newName: string) =>
    apiFetch<AdminDomainView>(`/admin/domains/${id}:rename`, {
      method: "POST",
      body: JSON.stringify({ new_name: newName }),
    }),
  deleteDomain: (id: string) =>
    apiFetch<void>(`/admin/domains/${id}`, { method: "DELETE" }),
  linkDomainToOm: (id: string, omDomainId: string) =>
    apiFetch<AdminDomainView>(`/admin/domains/${id}:link-to-om`, {
      method: "POST",
      body: JSON.stringify({ om_domain_id: omDomainId }),
    }),
  unlinkDomainFromOm: (id: string) =>
    apiFetch<AdminDomainView>(`/admin/domains/${id}:unlink-from-om`, {
      method: "POST",
      body: "{}",
    }),

  assignDomainOwnership: (domainId: string, body: AssignOwnershipRequest) =>
    apiFetch<AdminOwnershipView>(`/admin/domains/${domainId}/ownership`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  patchOwnership: (id: string, pinnedLocal: boolean) =>
    apiFetch<AdminOwnershipView>(`/admin/ownership/${id}`, {
      method: "PATCH",
      body: JSON.stringify({ pinned_local: pinnedLocal }),
    }),
  deleteOwnership: (id: string) =>
    apiFetch<void>(`/admin/ownership/${id}`, { method: "DELETE" }),

  renameCodeset: (id: string, newName: string, keepAlias = true) =>
    apiFetch<void>(`/admin/codesets/${id}:rename`, {
      method: "POST",
      body: JSON.stringify({
        new_name: newName,
        keep_alias_for_ingestion: keepAlias,
      }),
    }),
  deleteCodeset: (id: string, forceArchive = false) =>
    apiFetch<void>(
      `/admin/codesets/${id}${forceArchive ? "?force_archive=true" : ""}`,
      { method: "DELETE" },
    ),

  resolveAdminTask: (id: string, action: string, notes?: string) =>
    apiFetch<void>(`/admin/tasks/${id}:resolve`, {
      method: "POST",
      body: JSON.stringify({ action, notes: notes ?? null }),
    }),

  // Согласующие домена (directory) по domain_id. Добавляет/удаляет одного
  // кандидата (STEWARD | BUSINESS_OWNER), source RDM_ADMIN_LOCAL. Работает для
  // локальных доменов без om_domain_id (в отличие от reload).
  addDomainApprover: (
    domainId: string,
    body: {
      role: DirectoryRole;
      om_user_id: string;
      username?: string | null;
      display_name?: string | null;
    },
  ) =>
    apiFetch<Approver[]>(`/admin/domains/${domainId}/approvers`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  removeDomainApprover: (domainId: string, role: DirectoryRole, omUserId: string) =>
    apiFetch<void>(
      `/admin/domains/${domainId}/approvers?role=${role}&om_user_id=${encodeURIComponent(omUserId)}`,
      { method: "DELETE" },
    ),

  // E22 — заявки на удаление CodeSet'а.
  submitDeletionRequest: (codesetId: string, reason: string) =>
    apiFetch<DeletionRequestView>(
      `/codesets/${codesetId}/deletion-requests`,
      { method: "POST", body: JSON.stringify({ reason }) },
    ),
  cancelDeletionRequest: (id: string) =>
    apiFetch<void>(`/deletion-requests/${id}:cancel`, {
      method: "POST",
      body: "{}",
    }),
  approveDeletionRequest: (
    id: string,
    body: { decision_comment?: string | null; force_archive?: boolean },
  ) =>
    apiFetch<void>(`/admin/deletion-requests/${id}:approve`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  rejectDeletionRequest: (id: string, decisionComment: string) =>
    apiFetch<void>(`/admin/deletion-requests/${id}:reject`, {
      method: "POST",
      body: JSON.stringify({ decision_comment: decisionComment }),
    }),
};

// E22 — типы заявок.
export type DeletionRequestStatus =
  | "PENDING"
  | "APPROVED"
  | "REJECTED"
  | "CANCELLED";

export interface DeletionRequestView {
  id: string;
  codeset_id: string;
  codeset_name: string;
  domain_id: string;
  domain_name: string;
  requested_by: string;
  requested_by_username: string | null;
  reason: string;
  status: DeletionRequestStatus;
  decided_by: string | null;
  decided_by_username: string | null;
  decision_comment: string | null;
  created_at: string;
  decided_at: string | null;
  codeset_deleted: boolean;
  has_published_versions: boolean;
}

// Отчёт POST /relational/codesets/{id}/foreign-keys (Java records, camelCase).
export interface AppliedFk {
  fromColumn: string;
  toCodesetId: string;
  toTable: string;
  toColumn: string;
  constraint: string;
}
export interface SkippedFk {
  fromColumn: string;
  toCodesetId: string;
  reason: string;
}
export interface ForeignKeyReport {
  applied: AppliedFk[];
  skipped: SkippedFk[];
}

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

// E17 / BR-21: при to=IN_REVIEW (submit) assignee обязателен — Author
// выбирает домен + steward-учётку + business-owner-учётку.
export interface TransitionAssignee {
  domain_id: string;
  steward_om_user_id: string;
  owner_om_user_id: string;
}

export interface TransitionRequest {
  to: VersionStatus;
  comment?: string | null;
  expected_status?: VersionStatus | null;
  assignee?: TransitionAssignee | null;
}
