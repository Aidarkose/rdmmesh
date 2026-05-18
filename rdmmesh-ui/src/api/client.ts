import { userManager } from "@/auth/oidc";
import { config } from "@/config";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly body: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function getToken(): Promise<string | null> {
  const user = await userManager.getUser();
  if (!user || user.expired) return null;
  return user.access_token;
}

function buildUrl(path: string): string {
  if (path.startsWith("http")) return path;
  const base = config.api.base;
  const prefix = config.api.prefix;
  // path может приходить с или без префикса — нормализуем.
  const clean = path.startsWith("/") ? path : "/" + path;
  if (clean.startsWith(prefix)) return base + clean;
  return base + prefix + clean;
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await getToken();
  const headers = new Headers(init.headers ?? {});
  if (!headers.has("Accept")) headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type") && typeof init.body === "string") {
    headers.set("Content-Type", "application/json");
  }
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const res = await fetch(buildUrl(path), { ...init, headers });

  if (res.status === 204) {
    return undefined as T;
  }

  const ct = res.headers.get("content-type") ?? "";
  const isJson = ct.includes("application/json");
  const body: unknown = isJson ? await res.json().catch(() => null) : await res.text();

  if (!res.ok) {
    const msg = extractErrorMessage(body) ?? `${res.status} ${res.statusText}`;
    throw new ApiError(res.status, msg, body);
  }
  return body as T;
}

/**
 * E14 round 4 — raw-fetch для download'ов (audit export, в будущем — bulk-export
 * distribution'а). Возвращает {@link Response} без auto-парсинга — caller сам
 * читает blob/stream. JWT прокидывается так же как в {@link apiFetch}.
 *
 * <p>На non-2xx бросает {@link ApiError} с body как text (download endpoint'ы
 * обычно возвращают plain-text error либо ProblemDetails JSON).
 */
export async function apiFetchRaw(path: string, init: RequestInit = {}): Promise<Response> {
  const token = await getToken();
  const headers = new Headers(init.headers ?? {});
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const res = await fetch(buildUrl(path), { ...init, headers });
  if (!res.ok) {
    const ct = res.headers.get("content-type") ?? "";
    const body: unknown = ct.includes("application/json")
      ? await res.json().catch(() => null)
      : await res.text().catch(() => null);
    const msg = extractErrorMessage(body) ?? `${res.status} ${res.statusText}`;
    throw new ApiError(res.status, msg, body);
  }
  return res;
}

function extractErrorMessage(body: unknown): string | null {
  if (body == null) return null;
  if (typeof body === "string") return body;
  if (typeof body === "object") {
    const obj = body as Record<string, unknown>;
    if (typeof obj.message === "string") return obj.message;
    if (typeof obj.error === "string") return obj.error;
    if (Array.isArray(obj.errors) && obj.errors.length > 0) {
      return obj.errors.map(String).join("; ");
    }
  }
  return null;
}
