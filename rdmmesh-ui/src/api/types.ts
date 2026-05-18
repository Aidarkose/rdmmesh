// Wire-типы REST API. Backend смешивает naming:
// - generated spec POJO + @JsonProperty → snake_case;
// - Java records без @JsonProperty (AuthMe, ApprovalTask, ItemsPage из service'а) → camelCase.
// Поля помечены ровно так, как сериализуются на бэкенде.

// ─── Enums и базовые типы ──────────────────────────────────────────────────────

export type VersionStatus =
  | "DRAFT"
  | "IN_REVIEW"
  | "STEWARD_APPROVED"
  | "OWNER_APPROVED"
  | "PUBLISHED"
  | "DEPRECATED"
  | "REJECTED";

export type CodeItemStatus = "ACTIVE" | "RETIRED";

export type HierarchyMode = "NONE" | "INTRA_CODESET" | "CROSS_CODESET";

export type ReleaseChannel = "PROD" | "SANDBOX";

export type AssetRole = "OWNER" | "STEWARD" | "EXPERT" | "APPROVER";

export type Lang = "ru" | "en";

export interface LocalizedLabel {
  ru?: string | null;
  en?: string | null;
}

// ─── Catalog (snake_case) ──────────────────────────────────────────────────────

export interface Domain {
  id: string;
  om_domain_id: string;
  name: string;
  display_name?: string | null;
  description?: string | null;
  labels?: LocalizedLabel | null;
  tags?: string[] | null;
  created_at?: string | null;
  updated_at?: string | null;
}

export interface KeyPart {
  name: string;
  type: "STRING" | "INTEGER" | "BOOLEAN" | "DATE" | string;
}

export interface KeySpec {
  parts: KeyPart[];
}

export interface CodeSet {
  id: string;
  domain_id: string;
  name: string;
  display_name?: string | null;
  description?: string | null;
  labels?: LocalizedLabel | null;
  tags?: string[] | null;
  key_spec: KeySpec;
  hierarchy_mode: HierarchyMode;
  release_channels?: ReleaseChannel[] | null;
  current_published_version?: string | null;
  schema_version?: number | null;
  created_at?: string | null;
  created_by?: string | null;
  updated_at?: string | null;
}

export interface CodeSetSchemaDto {
  id: string;
  codeset_id: string;
  version: number;
  json_schema: Record<string, unknown>;
  created_at?: string | null;
  created_by?: string | null;
}

export interface CodeSetVersion {
  id: string;
  codeset_id: string;
  version: string;
  status: VersionStatus;
  schema_version: number;
  effective_from?: string | null;
  effective_to?: string | null;
  system_from?: string | null;
  system_to?: string | null;
  created_at?: string | null;
  created_by?: string | null;
  reviewed_by?: string[] | null;
  approved_by?: string | null;
  published_by?: string | null;
  published_at?: string | null;
  deprecated_at?: string | null;
  content_hash?: string | null;
  approval_signature?: string | null;
  owner_was_provisional?: boolean | null;
  release_channel?: ReleaseChannel | null;
  item_count?: number | null;
}

// ─── Authoring (snake_case) ────────────────────────────────────────────────────

export interface CodeItem {
  id: string;
  version_id: string;
  key_parts: string[];
  label_ru?: string | null;
  label_en?: string | null;
  description_ru?: string | null;
  description_en?: string | null;
  parent_key?: string[] | null;
  parent_ref?: Record<string, unknown> | null;
  attributes?: Record<string, unknown> | null;
  order_index?: number | null;
  status?: CodeItemStatus | null;
  effective_from?: string | null;
  effective_to?: string | null;
  system_from?: string | null;
  system_to?: string | null;
  row_version?: number | null;
}

// ItemsPage от AuthoringService — Java record без @JsonProperty → camelCase;
// HOWEVER `items` содержат CodeItemDto со snake_case полями.
export interface ItemsPage {
  page: number;
  size: number;
  total: number;
  items: CodeItem[];
}

// ─── Workflow ──────────────────────────────────────────────────────────────────

// WorkflowTransitionEvent — spec POJO + @JsonProperty → snake_case.
export interface WorkflowTransitionEvent {
  event_id: string;
  version_id: string;
  codeset_id?: string | null;
  domain_id?: string | null;
  from_status?: VersionStatus | null;
  to_status: VersionStatus;
  action: string;
  actor: string;
  occurred_at: string;
  comment?: string | null;
}

// MyTasksResource.ApprovalTaskDto — Java record без @JsonProperty → camelCase.
export interface ApprovalTask {
  id: string;
  versionId: string;
  codesetId: string;
  domainId: string;
  requiredRole: AssetRole | string;
  candidateUsers: string[];
  createdAt: string;
}

// ─── Auth ──────────────────────────────────────────────────────────────────────

// AuthResource.Me — Java record без @JsonProperty → camelCase.
export interface AuthMe {
  omUserId: string;
  keycloakSub: string;
  username: string;
  baseRoles: string[];
}

// ─── Verify (publishing) ───────────────────────────────────────────────────────

// VersionVerifyResource — VerifyResponse (Java record, camelCase).
export interface VerifyResponse {
  applicable: boolean;
  verified: boolean;
  computedHash?: string | null;
  storedHash?: string | null;
  note?: string | null;
}

// ─── Diff (snake_case, см. rdmmesh-spec/schema/api/version-diff.json) ─────────

export type DiffOp = "ADDED" | "CHANGED" | "REMOVED" | "MOVED";

export interface DiffEntry {
  op: DiffOp;
  key_parts: string[];
  changed_fields?: string[] | null;
  before?: unknown;
  after?: unknown;
}

export interface DiffSummary {
  added: number;
  changed: number;
  removed: number;
  moved: number;
}

export interface VersionDiffResponse {
  from_version: string;
  to_version: string;
  summary: DiffSummary;
  entries: DiffEntry[];
}

// ─── Subscriptions (snake_case, см. rdmmesh-spec/schema/api/webhook-subscription.json) ───

export type SubscriptionEvent = "VERSION_PUBLISHED" | "VERSION_DEPRECATED";

export type DeliveryStatus = "OK" | "FAILED" | "RETRYING";

export interface SubscriptionFilter {
  domains?: string[] | null;
  codesets?: string[] | null;
  events?: SubscriptionEvent[] | null;
}

export interface Subscription {
  id: string;
  url: string;
  secret_id: string;
  filter: SubscriptionFilter | Record<string, unknown>;
  active: boolean;
  created_at?: string | null;
  created_by?: string | null;
  last_delivery_at?: string | null;
  last_delivery_status?: DeliveryStatus | null;
}

// ─── Audit (snake_case, см. AuditResource в rdmmesh-audit) ────────────────────

export interface AuditEntry {
  id: number;
  event_id: string;
  event_type: string;
  aggregate_type?: string | null;
  aggregate_id?: string | null;
  actor?: string | null;
  occurred_at: string;
  payload: Record<string, unknown>;
}

export interface AuditPage {
  page: number;
  size: number;
  total: number;
  items: AuditEntry[];
}

export interface AuditFilters {
  eventType?: string | null;
  aggregateType?: string | null;
  aggregateId?: string | null;
  actor?: string | null;
  from?: string | null;
  to?: string | null;
  q?: string | null;
}

// E14 round 1: hash-chain verify. Snake_case — backend VerifyChainResponse.
// `verified=true` → first_broken_at/reason/expected_hash/stored_hash = null.
export interface AuditChainVerifyResult {
  from: number;
  to: number;
  checked: number;
  verified: boolean;
  first_broken_at?: number | null;
  reason?: string | null;
  expected_hash?: string | null;
  stored_hash?: string | null;
}

// ─── Distribution (camelCase, см. RdmDistributionResource — Java records без @JsonProperty) ───
//
// Внимание: формат отличается от authoring/ItemsPage (там snake_case).
// Distribution отдаёт уже-выбранный label (по lang), без раздельных label_ru/label_en.

export interface DistributionItem {
  keyParts: string[];
  parentKey?: string[] | null;
  label?: string | null;
  description?: string | null;
  attributes?: Record<string, unknown> | null;
  orderIndex?: number | null;
  status?: CodeItemStatus | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface DistributionItemsPage {
  domain: string;
  codeset: string;
  version: string;
  versionId: string;
  status: VersionStatus;
  contentHash?: string | null;
  publishedAt?: string | null;
  page: number;
  size: number;
  total: number;
  items: DistributionItem[];
}

// ─── Bulk import (snake_case, см. rdmmesh-spec/schema/api/bulk-import-result.json) ───

export type BulkStatus = "APPLIED" | "REJECTED";

export interface BulkImportError {
  row_index: number;
  key_parts?: string[] | null;
  field?: string | null;
  message: string;
}

export interface BulkImportResult {
  status: BulkStatus;
  rows_total: number;
  rows_added?: number | null;
  rows_updated?: number | null;
  rows_unchanged?: number | null;
  errors?: BulkImportError[] | null;
}
