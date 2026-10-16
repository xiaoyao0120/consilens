// 任务状态 → badge 类型与中文文案
export const TASK_STATUS_MAP = {
  PENDING: { label: "待调度", type: "warning" },
  CLAIMED: { label: "已认领", type: "info" },
  RUNNING: { label: "运行中", type: "info" },
  CANCEL_REQUESTED: { label: "取消中", type: "warning" },
  SUCCEEDED: { label: "成功", type: "success" },
  FAILED: { label: "失败", type: "error" },
  RETRYABLE: { label: "可重试", type: "warning" },
  CANCELLED: { label: "已取消", type: "default" },
};

export const TASK_STATUS_OPTIONS = Object.entries(TASK_STATUS_MAP).map(
  ([value, item]) => ({ label: item.label, value })
);

// artifact 类型 → badge 类型与中文文案
export const ARTIFACT_TYPE_MAP = {
  CONFIG: { label: "配置", type: "info" },
  VALIDATION_RESULT: { label: "校验结果", type: "warning" },
  DIAGNOSIS: { label: "诊断报告", type: "warning" },
  REPAIR_CONFIG: { label: "修复配置", type: "info" },
  RUN_RESULT: { label: "运行结果", type: "success" },
};

export const ARTIFACT_TYPE_OPTIONS = Object.entries(ARTIFACT_TYPE_MAP).map(
  ([value, item]) => ({ label: item.label, value })
);

// 节点状态 → badge 类型与中文文案
export const NODE_STATUS_MAP = {
  ONLINE: { label: "在线", type: "success" },
  OFFLINE: { label: "离线", type: "default" },
  REGISTERED: { label: "已注册", type: "warning" },
};

// 差异操作类型（与后端 DiffOperation 一致）
// SOURCE_MISSING：源端缺失 → 目标端存在该行（即“新增”）；TARGET_MISSING：目标端缺失 → 源端已删除（即“删除”）
export const DIFF_OPERATION_MAP = {
  MISMATCH: { label: "更新", type: "warning" },
  SOURCE_MISSING: { label: "新增", type: "success" },
  TARGET_MISSING: { label: "删除", type: "error" },
};

// 差异评分色阶（diff-report / diffSummary.status）
export const DIFF_SCORE_STATUS_MAP = {
  EXCELLENT: { label: "完全一致", type: "success" },
  GOOD: { label: "少量差异", type: "warning" },
  POOR: { label: "差异较多", type: "error" },
  NONE: { label: "暂无数据", type: "default" },
};

// badge 类型 → 语义色（引用 global.scss 的 CSS 变量，主题切换自动跟随；页面禁止另写 hex）
export const STATUS_TYPE_COLORS = {
  success: "var(--success)",
  warning: "var(--accent-amber)",
  error: "var(--danger)",
  info: "var(--primary)",
  primary: "var(--primary)",
  default: "var(--text-muted)",
};

export function statusTypeColor(type) {
  return STATUS_TYPE_COLORS[type] || STATUS_TYPE_COLORS.default;
}

// 数据源类型 → 图标色（仅后端 GET /v1/datasources/types 支持的类型）
export const DS_TYPE_COLORS = {
  mysql: "var(--primary)",
  doris: "var(--accent-amber)",
  hive: "var(--accent-green)",
  postgresql: "var(--violet)",
  clickhouse: "var(--danger)",
};

// 未知类型回退到中性色（列表必须能渲染未知类型的行）
export function datasourceTypeColor(type) {
  return DS_TYPE_COLORS[String(type || "").toLowerCase()] || "var(--text-muted)";
}
