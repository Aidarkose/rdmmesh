import { Alert, Spin } from "antd";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";

interface Props<T> {
  loading: boolean;
  error: string | null;
  data: T | null;
  children: (data: T) => ReactNode;
}

export function Loader<T>({ loading, error, data, children }: Props<T>) {
  const { t } = useTranslation();
  if (loading) {
    return (
      <div style={{ display: "grid", placeItems: "center", padding: 48 }}>
        <Spin tip={t("common.loading")} />
      </div>
    );
  }
  if (error) {
    return <Alert type="error" message={t("common.error")} description={error} showIcon />;
  }
  if (data == null) {
    return <Alert type="info" message={t("common.empty")} />;
  }
  return <>{children(data)}</>;
}
