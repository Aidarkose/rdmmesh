import { useMemo, type ReactNode } from "react";
import { Empty, Tag, Tree, Typography, App as AntApp } from "antd";
import type { TreeProps } from "antd";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { CodeItem } from "@/api/types";
import { apiMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  items: CodeItem[];
  versionId: string;
  codesetId: string;
  // drag-and-drop работает только в DRAFT (бэкенд блокирует UPDATE в других статусах).
  editable: boolean;
}

interface TreeNode {
  key: string; // serialized key_parts (JSON) — стабильный идентификатор для AntD Tree
  title: ReactNode;
  rawItem: CodeItem;
  children?: TreeNode[];
}

// Композитные ключи сериализуем одинаково для match'а parent_key ↔ key_parts.
function serializeKey(parts: string[] | null | undefined): string {
  if (!parts || parts.length === 0) return "";
  return JSON.stringify(parts);
}

function renderTitle(it: CodeItem): ReactNode {
  const label = it.label_ru || it.label_en || "";
  const keyText = Array.isArray(it.key_parts) ? it.key_parts.join(" / ") : String(it.key_parts);
  return (
    <span>
      <code style={{ marginRight: 8 }}>{keyText}</code>
      {label && <span style={{ color: "#555" }}>{label}</span>}
      {it.status === "RETIRED" && (
        <Tag color="default" style={{ marginLeft: 8 }}>
          RETIRED
        </Tag>
      )}
    </span>
  );
}

function buildForest(items: CodeItem[]): TreeNode[] {
  const map = new Map<string, TreeNode>();
  for (const it of items) {
    map.set(serializeKey(it.key_parts), {
      key: serializeKey(it.key_parts),
      title: renderTitle(it),
      rawItem: it,
    });
  }
  const roots: TreeNode[] = [];
  for (const it of items) {
    const node = map.get(serializeKey(it.key_parts))!;
    if (it.parent_key && it.parent_key.length > 0) {
      const parentKey = serializeKey(it.parent_key);
      const parent = map.get(parentKey);
      if (parent) {
        (parent.children ??= []).push(node);
        continue;
      }
      // parent_key указывает на несуществующий ключ (orphan) — считаем корнем.
    }
    roots.push(node);
  }
  // Стабильная сортировка по order_index затем по key.
  const sortRec = (nodes: TreeNode[]) => {
    nodes.sort((a, b) => {
      const oa = a.rawItem.order_index ?? 0;
      const ob = b.rawItem.order_index ?? 0;
      if (oa !== ob) return oa - ob;
      return a.key.localeCompare(b.key);
    });
    for (const n of nodes) {
      if (n.children) sortRec(n.children);
    }
  };
  sortRec(roots);
  return roots;
}

// Cycle-protection: target не должен быть descendant'ом drag-узла.
function isDescendantOf(node: TreeNode, targetKey: string): boolean {
  if (node.key === targetKey) return true;
  return node.children?.some((c) => isDescendantOf(c, targetKey)) ?? false;
}

function findNode(forest: TreeNode[], key: string): TreeNode | null {
  for (const n of forest) {
    if (n.key === key) return n;
    if (n.children) {
      const r = findNode(n.children, key);
      if (r) return r;
    }
  }
  return null;
}

export function HierarchyTree({ items, versionId, codesetId, editable }: Props) {
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();

  const forest = useMemo(() => buildForest(items), [items]);

  const patch = useMutation({
    mutationFn: ({ itemId, parentKey, expectedRowVersion }: {
      itemId: string;
      parentKey: string[] | null;
      expectedRowVersion: number;
    }) =>
      apiMutations.patchItem(versionId, itemId, {
        expected_row_version: expectedRowVersion,
        parent_key: parentKey,
      }),
    onSuccess: () => {
      message.success(t("tree.moveSuccess"));
      queryClient.invalidateQueries({ queryKey: qk.versions.itemsRoot(versionId) });
      queryClient.invalidateQueries({ queryKey: qk.versions.one(versionId) });
      queryClient.invalidateQueries({ queryKey: qk.versions.byCodeset(codesetId) });
    },
    onError: (e: unknown) => {
      if (e instanceof ApiError && e.status === 409) {
        message.error(t("items.optimisticConflict"));
        queryClient.invalidateQueries({ queryKey: qk.versions.itemsRoot(versionId) });
        return;
      }
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const onDrop: TreeProps["onDrop"] = (info) => {
    const dragKey = String(info.dragNode.key);
    const dropKey = String(info.node.key);
    const dropToGap = info.dropToGap;

    if (dragKey === dropKey) return;

    const dragNode = findNode(forest, dragKey);
    if (!dragNode) {
      message.error(t("tree.dropUnknown"));
      return;
    }

    // Запрет drop'а внутрь собственного поддерева — иначе цикл.
    if (isDescendantOf(dragNode, dropKey)) {
      message.error(t("tree.dropCycle"));
      return;
    }

    if (dragNode.rawItem.row_version == null) {
      message.error(t("tree.dropMissingRowVersion"));
      return;
    }

    let newParent: string[] | null;
    if (dropToGap) {
      // Drop рядом с target — становится sibling target'а; parent = parent of target.
      const targetItem = items.find((i) => serializeKey(i.key_parts) === dropKey);
      newParent = targetItem?.parent_key ?? null;
    } else {
      // Drop внутрь target — становится child target'а.
      const targetItem = items.find((i) => serializeKey(i.key_parts) === dropKey);
      newParent = targetItem?.key_parts ?? null;
    }

    // No-op: target = текущий parent. Не дёргаем backend.
    const currentParent = dragNode.rawItem.parent_key ?? null;
    const sameParent =
      (currentParent === null && newParent === null) ||
      (currentParent && newParent && JSON.stringify(currentParent) === JSON.stringify(newParent));
    if (sameParent) return;

    patch.mutate({
      itemId: dragNode.rawItem.id,
      parentKey: newParent,
      expectedRowVersion: dragNode.rawItem.row_version,
    });
  };

  if (items.length === 0) {
    return <Empty description={t("common.empty")} />;
  }

  return (
    <>
      {editable && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t("tree.dragHint")}
        </Typography.Text>
      )}
      <Tree
        treeData={forest}
        showLine
        defaultExpandAll
        draggable={editable ? { icon: false } : false}
        onDrop={editable ? onDrop : undefined}
        blockNode
        style={{ marginTop: 8 }}
      />
    </>
  );
}
