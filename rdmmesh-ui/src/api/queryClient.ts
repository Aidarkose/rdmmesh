import { QueryClient } from "@tanstack/react-query";

import { ApiError } from "./client";

// Один глобальный клиент. retry=1 для read-операций; mutations не ретраим (это решает caller).
// staleTime 30s — большинство экранов не меняются часто; mutations явно invalidate'ят свои keys.
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        // 4xx (включая 401/403/404/422) — не ретраим, ошибка пользовательская
        if (error instanceof ApiError && error.status < 500) return false;
        return failureCount < 1;
      },
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: false,
    },
  },
});

// Централизованная фабрика query keys. Совпадение префикса важно для invalidate'ов
// (например, qk.versions.byCodeset(id) и qk.versions.one(vid) — разные деревья).
export const qk = {
  auth: {
    me: () => ["auth", "me"] as const,
  },
  domains: {
    all: () => ["domains"] as const,
    one: (id: string) => ["domains", id] as const,
  },
  codesets: {
    byDomain: (domainId: string) => ["codesets", "by-domain", domainId] as const,
    one: (id: string) => ["codesets", id] as const,
    schema: (codesetId: string) => ["codesets", codesetId, "schema"] as const,
    schemaHistory: (codesetId: string) => ["codesets", codesetId, "schema", "history"] as const,
  },
  versions: {
    byCodeset: (codesetId: string) => ["versions", "by-codeset", codesetId] as const,
    one: (id: string) => ["versions", id] as const,
    items: (id: string, page: number, size: number) => ["versions", id, "items", page, size] as const,
    itemsRoot: (id: string) => ["versions", id, "items"] as const,
    history: (id: string) => ["versions", id, "history"] as const,
    verify: (id: string) => ["versions", id, "verify"] as const,
    diff: (toId: string, fromId: string) => ["versions", toId, "diff", fromId] as const,
  },
  tasks: {
    my: () => ["tasks", "my"] as const,
  },
  subscriptions: {
    all: () => ["subscriptions"] as const,
    one: (id: string) => ["subscriptions", id] as const,
  },
  distribution: {
    items: (
      domain: string,
      codeset: string,
      opts: {
        version: string | null;
        asOf: string | null;
        knowledgeAsOf: string | null;
        lang: string | null;
        page: number;
        size: number;
      },
    ) => ["distribution", "items", domain, codeset, opts] as const,
  },
  audit: {
    // queryKey включает все фильтры — при их смене React Query автоматически
    // делает refetch без manual invalidate. Стабильность ключа важна: одинаковые
    // фильтры (включая null vs undefined) должны дать одинаковый key.
    list: (
      page: number,
      size: number,
      filters: {
        eventType: string | null;
        aggregateType: string | null;
        aggregateId: string | null;
        actor: string | null;
        from: string | null;
        to: string | null;
        q: string | null;
      },
    ) => ["audit", "list", page, size, filters] as const,
    // E14 round 1 — verify hash-chain. Ключ включает range, чтобы separate
    // запросы full-chain и sub-range кэшировались независимо.
    verify: (fromId: number | null, toId: number | null) =>
      ["audit", "verify", fromId, toId] as const,
  },
};
