import { useAppStore } from "@/store";

/**
 * shadcn 风格 naive-ui 主题定制。
 * 亮色：蓝主色 blue-600，暗色：blue-500；中性色阶 + 细边框 + 6px 圆角。
 * 与 global.scss 中的 CSS 变量（--bg/--card/--border/...）配合使用。
 */
const lightOverrides = {
  common: {
    primaryColor: "#2563eb",
    primaryColorHover: "#3b82f6",
    primaryColorPressed: "#1d4ed8",
    primaryColorSuppl: "#2563eb",
    infoColor: "#2563eb",
    successColor: "#16a34a",
    warningColor: "#d97706",
    errorColor: "#dc2626",
    borderRadius: "6px",
    borderRadiusSmall: "4px",
    fontFamily:
      '-apple-system, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif',
  },
  Card: {
    borderColor: "#e4e4e7",
    boxShadow: "0 1px 2px 0 rgb(0 0 0 / 0.05)",
    borderRadius: "6px",
    color: "#ffffff",
  },
  DataTable: {
    thColor: "#fafafa",
    thTextColor: "#71717a",
    thFontWeight: "500",
    borderColor: "#e4e4e7",
    tdColorHover: "rgba(37, 99, 235, 0.04)",
  },
  Button: { borderRadiusMedium: "6px", borderRadiusSmall: "4px" },
  Tag: { borderRadius: "999px" },
  Input: {
    borderRadius: "6px",
    border: "1px solid #d4d4d8",
    borderHover: "1px solid #3b82f6",
    borderFocus: "1px solid #2563eb",
    boxShadowFocus: "0 0 0 2px rgba(37, 99, 235, 0.15)",
  },
  Tabs: { tabBorderRadius: "6px", tabFontSizeMedium: "14px" },
  Drawer: { borderRadius: "8px 0 0 8px" },
  Empty: { iconColor: "#a1a1aa" },
  LoadingBar: { colorLoading: "#2563eb" },
  Progress: { railColor: "#e4e4e7" },
  Alert: { borderRadius: "6px" },
};

const darkOverrides = {
  common: {
    primaryColor: "#3b82f6",
    primaryColorHover: "#60a5fa",
    primaryColorPressed: "#2563eb",
    primaryColorSuppl: "#3b82f6",
    infoColor: "#3b82f6",
    successColor: "#22c55e",
    warningColor: "#f59e0b",
    errorColor: "#ef4444",
    borderRadius: "6px",
    borderRadiusSmall: "4px",
    fontFamily:
      '-apple-system, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif',
  },
  Card: {
    borderColor: "#27272a",
    boxShadow: "0 1px 2px 0 rgb(0 0 0 / 0.4)",
    borderRadius: "6px",
    color: "#18181b",
  },
  DataTable: {
    thColor: "#18181b",
    thTextColor: "#a1a1aa",
    thFontWeight: "500",
    borderColor: "#27272a",
    tdColorHover: "rgba(59, 130, 246, 0.08)",
  },
  Button: { borderRadiusMedium: "6px", borderRadiusSmall: "4px" },
  Tag: { borderRadius: "999px" },
  Input: {
    borderRadius: "6px",
    border: "1px solid #3f3f46",
    borderHover: "1px solid #60a5fa",
    borderFocus: "1px solid #3b82f6",
    boxShadowFocus: "0 0 0 2px rgba(59, 130, 246, 0.25)",
  },
  Tabs: { tabBorderRadius: "6px", tabFontSizeMedium: "14px" },
  Drawer: { borderRadius: "8px 0 0 8px" },
  Empty: { iconColor: "#71717a" },
  LoadingBar: { colorLoading: "#3b82f6" },
  Progress: { railColor: "#27272a" },
  Alert: { borderRadius: "6px" },
};

export function naiveTheme() {
  const store = useAppStore();
  return store.theme === "dark" ? darkOverrides : lightOverrides;
}
