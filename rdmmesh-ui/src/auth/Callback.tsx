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

// Одноразовое самовосстановление: если код невалиден/просрочен (stale
// PKCE-state после logout→login «сменить пользователя»), вместо тупикового
// экрана ошибки чистим state и стартуем свежий signinRedirect РОВНО один
// раз. Флаг в sessionStorage переживает редирект на Keycloak и обратно и не
// даёт зациклиться, если сломано фундаментально.
const RETRY_KEY = "rdm_oidc_recovering";

export function Callback() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    const finishHome = () => {
      sessionStorage.removeItem(RETRY_KEY);
      if (!cancelled) navigate("/", { replace: true });
    };

    const run = async () => {
      const params = new URLSearchParams(window.location.search);
      // Нет ?code — это не настоящий auth-callback (рефреш, возврат после
      // logout и т.п.). Не дёргаем обмен, уходим домой: ProtectedRoute сам
      // решит, нужен ли вход.
      if (!params.has("code")) {
        finishHome();
        return;
      }
      // Код уже обменян ранее (двойной маунт / повторный заход на URL) и
      // валидный пользователь уже есть — повторно не обмениваем.
      const existing = await userManager.getUser();
      if (existing && !existing.expired) {
        finishHome();
        return;
      }
      await handleCallbackOnce();
      finishHome();
    };

    run().catch((e: unknown) => {
      if (cancelled) return;
      const recovering = sessionStorage.getItem(RETRY_KEY) === "1";
      if (!recovering) {
        // Первая неудача (обычно «Code not valid» из-за stale PKCE-state):
        // чистим и логинимся заново — ровно одна попытка.
        sessionStorage.setItem(RETRY_KEY, "1");
        callbackOnce = null;
        void userManager
          .clearStaleState()
          .finally(() => void userManager.signinRedirect());
        return;
      }
      // Повторная неудача — терминальная ошибка (без зацикливания).
      sessionStorage.removeItem(RETRY_KEY);
      setError(e instanceof Error ? e.message : String(e));
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
