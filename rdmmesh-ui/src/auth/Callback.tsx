import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Alert, Spin } from "antd";
import { userManager } from "./oidc";

// Authorization code одноразовый. React 18 <StrictMode> монтирует useEffect
// дважды в dev → без этого мьютекса второй signinRedirectCallback() обменивает
// уже использованный код и Keycloak возвращает "Code not valid". Промис на
// уровне модуля гарантирует ровно один обмен; оба маунта ждут один результат.
let callbackOnce: Promise<unknown> | null = null;
function handleCallbackOnce(): Promise<unknown> {
  if (!callbackOnce) {
    callbackOnce = userManager.signinRedirectCallback();
  }
  return callbackOnce;
}

export function Callback() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    handleCallbackOnce()
      .then(() => {
        if (!cancelled) navigate("/", { replace: true });
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  if (error) {
    return (
      <div style={{ padding: 24 }}>
        <Alert type="error" message="Ошибка входа" description={error} showIcon />
      </div>
    );
  }
  return (
    <div style={{ display: "grid", placeItems: "center", height: "100vh" }}>
      <Spin tip="Завершаем вход..." size="large" />
    </div>
  );
}
