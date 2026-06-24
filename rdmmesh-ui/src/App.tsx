import { Navigate, Route, Routes } from "react-router-dom";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";

import { Callback } from "@/auth/Callback";
import { ProtectedRoute } from "@/auth/ProtectedRoute";
import { AppLayout } from "@/layout/AppLayout";
import { CatalogPage } from "@/pages/CatalogPage";
import { CodeSetPage } from "@/pages/CodeSetPage";
import { DomainPage } from "@/pages/DomainPage";
import { LoginPage } from "@/pages/LoginPage";
import { AuditPage } from "@/pages/AuditPage";
import { AdminDomainsPage } from "@/pages/AdminDomainsPage";
import { AdminDeletionRequestsPage } from "@/pages/AdminDeletionRequestsPage";
import { MyTasksPage } from "@/pages/MyTasksPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { SubscriptionsPage } from "@/pages/SubscriptionsPage";
import { VersionPage } from "@/pages/VersionPage";

export function App() {
  return (
    <>
      <Routes>
        <Route path="/callback" element={<Callback />} />
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <ProtectedRoute>
              <AppLayout />
            </ProtectedRoute>
          }
        >
          <Route index element={<Navigate to="/catalog" replace />} />
          <Route path="/catalog" element={<CatalogPage />} />
          <Route path="/domains/:domainId" element={<DomainPage />} />
          <Route path="/codesets/:codesetId" element={<CodeSetPage />} />
          <Route path="/versions/:versionId" element={<VersionPage />} />
          <Route path="/tasks" element={<MyTasksPage />} />
          <Route path="/admin/domains" element={<AdminDomainsPage />} />
          <Route path="/admin/deletion-requests" element={<AdminDeletionRequestsPage />} />
          <Route path="/admin/subscriptions" element={<SubscriptionsPage />} />
          <Route path="/admin/audit" element={<AuditPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
      {import.meta.env.DEV && <ReactQueryDevtools initialIsOpen={false} buttonPosition="bottom-left" />}
    </>
  );
}
