<script setup>
import { computed } from "vue";
import { NIcon, NTag, NProgress } from "naive-ui";
import {
  ConstructOutline,
  CheckmarkCircleOutline,
  AlertCircleOutline,
  BanOutline,
} from "@vicons/ionicons5";

// 工具状态行：显示工具标签、loading、成功/失败与安全摘要；不展示 redacted args
const props = defineProps({
  // started | progress | completed | blocked | failed
  status: { type: String, default: "started" },
  toolName: { type: String, default: "" },
  label: { type: String, default: "" },
  message: { type: String, default: "" },
  percent: { type: Number, default: null },
});

const meta = computed(() => {
  switch (props.status) {
    case "completed":
      return { icon: CheckmarkCircleOutline, color: "var(--success)", tagType: "success", text: "完成" };
    case "blocked":
      return { icon: BanOutline, color: "var(--warning)", tagType: "warning", text: "已拦截" };
    case "failed":
      return { icon: AlertCircleOutline, color: "var(--danger)", tagType: "error", text: "失败" };
    case "progress":
      return { icon: ConstructOutline, color: "var(--primary)", tagType: "info", text: "执行中" };
    default:
      return { icon: ConstructOutline, color: "var(--primary)", tagType: "info", text: "开始" };
  }
});

const displayLabel = computed(() => props.label || props.toolName || "工具调用");
</script>

<template>
  <div class="tool-status">
    <n-icon :size="14" :color="meta.color">
      <component :is="meta.icon" />
    </n-icon>
    <div class="tool-body">
      <div class="tool-head">
        <span class="tool-label">{{ displayLabel }}</span>
        <n-tag size="small" :bordered="false" :type="meta.tagType" round>{{ meta.text }}</n-tag>
      </div>
      <div v-if="message" class="tool-message">{{ message }}</div>
      <n-progress
        v-if="status === 'progress' && percent !== null && percent !== undefined"
        type="line"
        :percentage="percent"
        :height="4"
        :show-indicator="false"
        :border-radius="2"
        class="tool-progress"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
.tool-status {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;

  .tool-body {
    flex: 1;
    min-width: 0;
  }

  .tool-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .tool-label {
    font-size: 13px;
    font-weight: 500;
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .tool-message {
    margin-top: 4px;
    font-size: 12px;
    color: var(--text-secondary);
    word-break: break-word;
  }

  .tool-progress {
    margin-top: 6px;
  }
}
</style>
