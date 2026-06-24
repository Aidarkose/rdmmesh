import { Segmented } from "antd";
import { useTranslation } from "react-i18next";

export function LangSwitcher() {
  const { i18n } = useTranslation();
  const value = i18n.resolvedLanguage === "en" ? "en" : "ru";
  return (
    <Segmented
      value={value}
      options={[
        { label: "RU", value: "ru" },
        { label: "EN", value: "en" },
      ]}
      onChange={(v) => {
        void i18n.changeLanguage(String(v));
      }}
    />
  );
}
