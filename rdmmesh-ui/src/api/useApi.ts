import { useQuery, type QueryKey } from "@tanstack/react-query";

import { ApiError } from "./client";

// Тонкая обёртка над useQuery, отдающая Loader-совместимый shape {loading, error, data}.
// На read-only Round 1 это был самописный hook без кэширования; в Round 2 — useQuery
// под капотом, чтобы mutations могли invalidate'ить через queryClient.invalidateQueries.
export interface ApiState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

// Перегрузка с явным queryKey — основной путь, чтобы invalidate'ы из mutation hooks
// попадали в нужные queries.
export function useApi<T>(loader: () => Promise<T>, queryKey: QueryKey): ApiState<T> {
  const q = useQuery<T>({
    queryKey,
    queryFn: loader,
  });

  return {
    data: q.data ?? null,
    loading: q.isLoading,
    error: q.error ? formatError(q.error) : null,
  };
}

function formatError(e: unknown): string {
  if (e instanceof ApiError) return `${e.status}: ${e.message}`;
  if (e instanceof Error) return e.message;
  return String(e);
}
