import { UserManager, WebStorageStateStore } from "oidc-client-ts";
import { config } from "@/config";

export const userManager = new UserManager({
  authority: config.oidc.authority,
  client_id: config.oidc.clientId,
  redirect_uri: config.oidc.redirectUri,
  post_logout_redirect_uri: config.oidc.postLogoutRedirectUri,
  response_type: "code",
  scope: config.oidc.scope,
  userStore: new WebStorageStateStore({ store: window.localStorage }),
  // PKCE включён по умолчанию для response_type=code в oidc-client-ts.
  // Молчаливый refresh — через iframe нерекомендован; refresh_token в Keycloak
  // realm-bank.json выдаётся, oidc-client-ts использует его автоматически когда
  // accessTokenExpiringNotificationTimeInSeconds сработает.
  automaticSilentRenew: true,
  loadUserInfo: false,
});
