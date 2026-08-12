<script setup>
import { computed } from "vue";

const props = defineProps({
  status: { type: String, default: "" },
  label: { type: String, default: "" },
  // 可选状态映射：{ STATUS: { label, type } }，缺省使用内置映射
  map: { type: Object, default: null },
});

const builtinMap = {
  PENDING: { label: "待调度", type: "info" },
  CLAIMED: { label: "已认领", type: "info" },
  RUNNING: { label: "运行中", type: "success" },
  CANCEL_REQUESTED: { label: "取消中", type: "warning" },
  SUCCEEDED: { label: "成功", type: "success" },
  FAILED: { label: "失败", type: "error" },
  RETRYABLE: { label: "可重试", type: "warning" },
  CANCELLED: { label: "已取消", type: "default" },
  ONLINE: { label: "在线", type: "success" },
  OFFLINE: { label: "离线", type: "default" },
  REGISTERED: { label: "已注册", type: "warning" },
  CONFIG: { label: "配置", type: "info" },
  VALIDATION_RESULT: { label: "校验结果", type: "warning" },
  DIAGNOSIS: { label: "诊断报告", type: "warning" },
  REPAIR_CONFIG: { label: "修复配置", type: "info" },
  RUN_RESULT: { label: "运行结果", type: "success" },
  INSERT: { label: "新增", type: "success" },
  UPDATE: { label: "更新", type: "warning" },
  DELETE: { label: "删除", type: "error" },
  MISMATCH: { label: "更新", type: "warning" },
  SOURCE_MISSING: { label: "新增", type: "success" },
  TARGET_MISSING: { label: "删除", type: "error" },
  EXCELLENT: { label: "完全一致", type: "success" },
  GOOD: { label: "少量差异", type: "warning" },
  POOR: { label: "差异较多", type: "error" },
  NONE: { label: "暂无数据", type: "default" },
};

const meta = computed(() => {
  const map = props.map || builtinMap;
  return map[props.status] || { label: props.status || "-", type: "default" };
});
</script>

<template>
  <n-tag :type="meta.type" size="small" :bordered="false" round>
    <template #icon>
      <span class="state-dot" :class="meta.type" />
    </template>
    {{ label || meta.label }}
  </n-tag>
</template>

<style scoped lang="scss">
.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
</style>
