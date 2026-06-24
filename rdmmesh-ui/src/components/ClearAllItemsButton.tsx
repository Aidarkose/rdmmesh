import { useState } from "react";
import { App as AntApp, Button, Input, Modal, Typography } from "antd";
import { ClearOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  versionId: string;
}

const CONFIRM_PHRASE = "clear-all";

export function ClearAllItemsButton({ versionId }: Props) {
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");

  const clear = useMutation({
    mutationFn: () => apiMutations.clearAllItems(versionId),
    onSuccess: ({ deleted }) => {
      message.success(t("items.clearAll.success", { n: deleted }));
      queryClient.invalidateQueries({ queryKey: qk.versions.itemsRoot(versionId) });
      queryClient.invalidateQueries({ queryKey: qk.versions.one(versionId) });
      setOpen(false);
      setTyped("");
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  return (
    <>
      <Button danger icon={<ClearOutlined />} onClick={() => setOpen(true)}>
        {t("items.clearAll.button")}
      </Button>
      <Modal
        open={open}
        title={t("items.clearAll.title")}
        okText={t("items.clearAll.okText")}
        okButtonProps={{ danger: true, disabled: typed !== CONFIRM_PHRASE }}
        cancelText={t("workflow.modal.cancel")}
        confirmLoading={clear.isPending}
        onOk={() => clear.mutate()}
        onCancel={() => {
          setOpen(false);
          setTyped("");
        }}
      >
        <Typography.Paragraph>
          {t("items.clearAll.description")}
        </Typography.Paragraph>
        <Typography.Paragraph>
          {t("items.clearAll.confirmHint")}{" "}
          <Typography.Text code>{CONFIRM_PHRASE}</Typography.Text>
        </Typography.Paragraph>
        <Input
          autoFocus
          placeholder={CONFIRM_PHRASE}
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          onPressEnter={() => {
            if (typed === CONFIRM_PHRASE) clear.mutate();
          }}
        />
      </Modal>
    </>
  );
}
