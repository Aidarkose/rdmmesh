import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { User } from "oidc-client-ts";
import { userManager } from "./oidc";

interface AuthState {
  ready: boolean;
  user: User | null;
  baseRoles: readonly string[];
  username: string | null;
  login: () => Promise<void>;
  logout: () => Promise<void>;
}

const AuthCtx = createContext<AuthState | null>(null);

function extractBaseRoles(user: User | null): string[] {
  if (!user) return [];
  const claim = (user.profile as Record<string, unknown>).groups;
  if (Array.isArray(claim)) {
    return claim.map((g) => String(g).replace(/^\//, "")).filter(Boolean);
  }
  return [];
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    userManager
      .getUser()
      .then((u) => {
        if (cancelled) return;
        setUser(u && !u.expired ? u : null);
      })
      .finally(() => {
        if (!cancelled) setReady(true);
      });

    const onLoaded = (u: User) => setUser(u);
    const onUnloaded = () => setUser(null);
    const onExpired = () => setUser(null);

    userManager.events.addUserLoaded(onLoaded);
    userManager.events.addUserUnloaded(onUnloaded);
    userManager.events.addAccessTokenExpired(onExpired);

    return () => {
      cancelled = true;
      userManager.events.removeUserLoaded(onLoaded);
      userManager.events.removeUserUnloaded(onUnloaded);
      userManager.events.removeAccessTokenExpired(onExpired);
    };
  }, []);

  const value = useMemo<AuthState>(() => {
    return {
      ready,
      user,
      baseRoles: extractBaseRoles(user),
      username:
        (user?.profile as Record<string, unknown> | undefined)?.preferred_username
          ? String((user!.profile as Record<string, unknown>).preferred_username)
          : null,
      login: () => userManager.signinRedirect(),
      // Перед end-session ОБЯЗАТЕЛЬНО снимаем локального юзера и чистим
      // stale signin-state. Иначе при «сменить пользователя» остаётся
      // мусорный oidc.<state> с чужим PKCE code_verifier, и обмен кода
      // следующего логина падает в Keycloak с invalid_grant
      // («Code not valid»). signoutRedirectCallback на post-logout мы не
      // обрабатываем (post_logout → "/"), поэтому чистим здесь явно.
      logout: async () => {
        await userManager.removeUser();
        await userManager.clearStaleState();
        await userManager.signoutRedirect();
      },
    };
  }, [ready, user]);

  return <AuthCtx.Provider value={value}>{children}</AuthCtx.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}
