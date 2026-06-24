import { Avatar, Dropdown, Space, Tag, Typography } from "antd";
import { LogoutOutlined, UserOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";

import { useAuth } from "@/auth/AuthContext";

export function UserMenu() {
  const { t } = useTranslation();
  const { username, baseRoles, logout } = useAuth();
  if (!username) return null;

  return (
    <Dropdown
      trigger={["click"]}
      menu={{
        items: [
          {
            key: "info",
            disabled: true,
            label: (
              <div style={{ minWidth: 220 }}>
                <Typography.Text strong>{username}</Typography.Text>
                <div style={{ marginTop: 6 }}>
                  {baseRoles.length > 0 ? (
                    baseRoles.map((r) => (
                      <Tag key={r} color="blue" style={{ marginBottom: 4 }}>
                        {r}
                      </Tag>
                    ))
                  ) : (
                    <Typography.Text type="secondary">—</Typography.Text>
                  )}
                </div>
              </div>
            ),
          },
          { type: "divider" },
          {
            key: "logout",
            icon: <LogoutOutlined />,
            label: t("nav.logout"),
            onClick: () => {
              void logout();
            },
          },
        ],
      }}
    >
      <Space style={{ cursor: "pointer" }}>
        <Avatar size="small" icon={<UserOutlined />} />
        <Typography.Text>{username}</Typography.Text>
      </Space>
    </Dropdown>
  );
}
