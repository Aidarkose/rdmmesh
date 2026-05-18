import { StrictMode, Suspense } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { ConfigProvider, App as AntApp, Spin } from "antd";
import ruRU from "antd/locale/ru_RU";
import enUS from "antd/locale/en_US";
import { useTranslation } from "react-i18next";
import { QueryClientProvider } from "@tanstack/react-query";

import "@/i18n";
import { AuthProvider } from "@/auth/AuthContext";
import { App } from "@/App";
import { queryClient } from "@/api/queryClient";

function LocaleConfigProvider({ children }: { children: React.ReactNode }) {
  const { i18n } = useTranslation();
  const locale = i18n.resolvedLanguage === "en" ? enUS : ruRU;
  return (
    <ConfigProvider locale={locale} theme={{ token: { colorPrimary: "#1677ff" } }}>
      <AntApp>{children}</AntApp>
    </ConfigProvider>
  );
}

const root = document.getElementById("root");
if (!root) throw new Error("#root not found in index.html");

createRoot(root).render(
  <StrictMode>
    <Suspense fallback={<Spin size="large" />}>
      <QueryClientProvider client={queryClient}>
        <LocaleConfigProvider>
          <BrowserRouter>
            <AuthProvider>
              <App />
            </AuthProvider>
          </BrowserRouter>
        </LocaleConfigProvider>
      </QueryClientProvider>
    </Suspense>
  </StrictMode>,
);
