import { Tag } from "antd";
import type { VersionStatus } from "@/api/types";

const COLOR: Record<VersionStatus, string> = {
  DRAFT: "default",
  IN_REVIEW: "processing",
  STEWARD_APPROVED: "cyan",
  OWNER_APPROVED: "geekblue",
  PUBLISHED: "green",
  DEPRECATED: "warning",
  REJECTED: "error",
};

export function StatusTag({ status }: { status: VersionStatus }) {
  return <Tag color={COLOR[status] ?? "default"}>{status}</Tag>;
}
