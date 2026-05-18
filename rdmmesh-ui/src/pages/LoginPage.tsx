import { useEffect } from "react";
import { Button, Card, Spin, Typography } from "antd";
import { LoginOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { useAuth } from "@/auth/AuthContext";

export function LoginPage() {
  const { t } = useTranslation();
  const { ready, user, login } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (ready && user) navigate("/", { replace: true });
  }, [ready, user, navigate]);

  if (!ready) {
    return (
      <div style={{ display: "grid", placeItems: "center", height: "100vh" }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div style={{ display: "grid", placeItems: "center", height: "100vh", padding: 16 }}>
      <Card style={{ maxWidth: 420, width: "100%" }}>
        <Typography.Title level={3} style={{ marginTop: 0 }}>
          {t("login.headline")}
        </Typography.Title>
        <Typography.Paragraph type="secondary">{t("login.description")}</Typography.Paragraph>
        <Button type="primary" icon={<LoginOutlined />} onClick={() => void login()} block size="large">
          {t("login.button")}
        </Button>
      </Card>
    </div>
  );
}
