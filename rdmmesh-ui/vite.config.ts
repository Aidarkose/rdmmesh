import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// Vite proxy цели:
//   /api/*      -> rdmmesh-service (Dropwizard, http://localhost:8080)
//   /healthcheck   -> rdmmesh-service admin port (8081) — для дебага из dev-server'а
// Keycloak (8090) UI обращается напрямую: oidc-client-ts работает с iss-URL'ом,
// прокси через Vite только усложнил бы CORS/redirect-семантику.

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  const apiTarget = env.VITE_DEV_API_TARGET ?? "http://localhost:8080";

  return {
    plugins: [react()],
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "src"),
      },
    },
    server: {
      port: 5173,
      strictPort: true,
      host: "0.0.0.0",
      proxy: {
        "/api": {
          target: apiTarget,
          changeOrigin: true,
        },
      },
    },
    build: {
      sourcemap: true,
      outDir: "dist",
      emptyOutDir: true,
      // Code-splitting — handoff E11 §3 #1 / E11.2a §4: до E11.2b жил один большой chunk.
      // Делим на vendor/antd/query, чтобы initial gzip упал ниже 300 KB и кэш браузера
      // переживал релизы app-кода без перевыкачки antd/react.
      rollupOptions: {
        output: {
          manualChunks: {
            "vendor-react": ["react", "react-dom", "react-router-dom"],
            "vendor-antd": ["antd", "@ant-design/icons"],
            "vendor-query": [
              "@tanstack/react-query",
              "@tanstack/react-table",
              "oidc-client-ts",
              "i18next",
              "react-i18next",
              "i18next-browser-languagedetector",
            ],
          },
        },
      },
    },
  };
});
