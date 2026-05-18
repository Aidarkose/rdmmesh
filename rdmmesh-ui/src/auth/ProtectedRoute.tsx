import { useEffect, type ReactNode } from "react";
import { Spin } from "antd";
import { useAuth } from "./AuthContext";
import { userManager } from "./oidc";

// signinRedirect одноразовый: каждый вызов кладёт в storage новый
// oidc.<state> со своим PKCE code_verifier и навигирует на Keycloak.
// React 18 <StrictMode> прогоняет useEffect дважды → без этого мьютекса
// два signinRedirect создают два конкурирующих state'а, и обмен кода
// (PKCE) ловит чужой verifier → Keycloak invalid_grant «Code not valid».
// Модульный флаг гарантирует ровно один redirect; страница всё равно
// уходит на Keycloak, так что сброс флага не нужен.
let redirecting = false;

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { ready, user, login } = useAuth();

  useEffect(() => {
    if (ready && !user && !redirecting) {
      redirecting = true;
      // Снимаем накопившийся stale-state перед свежим signinRedirect,
      // чтобы Callback не подобрал верификатор от прошлой попытки.
      void userManager.clearStaleState().finally(() => void login());
    }
  }, [ready, user, login]);

  if (!ready || !user) {
    return (
      <div style={{ display: "grid", placeItems: "center", height: "100vh" }}>
        <Spin size="large" />
      </div>
    );
  }
  return <>{children}</>;
}
