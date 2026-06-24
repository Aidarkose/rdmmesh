import { Result, Button } from "antd";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <Result
      status="404"
      title="404"
      subTitle={t("common.empty")}
      extra={
        <Link to="/catalog">
          <Button type="primary">{t("nav.catalog")}</Button>
        </Link>
      }
    />
  );
}
