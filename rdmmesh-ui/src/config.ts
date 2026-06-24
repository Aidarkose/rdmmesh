export const config = {
  oidc: {
    authority: import.meta.env.VITE_OIDC_AUTHORITY ?? "http://localhost:8090/realms/bank",
    clientId: import.meta.env.VITE_OIDC_CLIENT_ID ?? "rdmmesh-ui",
    redirectUri: window.location.origin + "/callback",
    postLogoutRedirectUri: window.location.origin + "/",
    scope: "openid profile email",
  },
  api: {
    base: import.meta.env.VITE_API_BASE ?? "",
    prefix: "/api/v1",
  },
} as const;
