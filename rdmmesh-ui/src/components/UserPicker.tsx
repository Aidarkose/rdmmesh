import { useState } from "react";
import { Select, Spin } from "antd";
import { useQuery } from "@tanstack/react-query";

import { adminApi, type AdminUserView } from "@/api/endpoints";
import { qk } from "@/api/queryClient";

interface UserPickerProps {
  value?: string | null;
  onChange?: (omUserId: string | null) => void;
  // даёт полную карточку выбранного пользователя (username/display_name/email),
  // когда вызывающему нужны не только id (напр. для записи в directory).
  onPick?: (user: AdminUserView | null) => void;
  placeholder?: string;
  disabled?: boolean;
}

/**
 * Autocomplete-выбор пользователя из identity.rdm_user_mapping (E18.4).
 * Поиск идёт по username / display_name / email через GET /admin/users/search.
 * Видны только пользователи, которые хоть раз логинились в RDM (E2).
 */
export function UserPicker({ value, onChange, onPick, placeholder, disabled }: UserPickerProps) {
  const [q, setQ] = useState("");

  const search = useQuery({
    queryKey: qk.admin.userSearch(q),
    queryFn: () => adminApi.searchUsers(q),
    enabled: q.length >= 1,
    staleTime: 10_000,
  });

  return (
    <Select
      showSearch
      allowClear
      filterOption={false}
      value={value ?? undefined}
      placeholder={placeholder ?? "Search user…"}
      disabled={disabled}
      style={{ width: "100%" }}
      notFoundContent={search.isFetching ? <Spin size="small" /> : null}
      onSearch={(text) => setQ(text)}
      onChange={(v) => {
        onChange?.(v ?? null);
        onPick?.((search.data ?? []).find((u) => u.om_user_id === v) ?? null);
      }}
      options={(search.data ?? []).map((u) => ({
        value: u.om_user_id,
        label: `${u.display_name ?? u.username} (${u.username})${u.email ? " · " + u.email : ""}`,
      }))}
    />
  );
}
