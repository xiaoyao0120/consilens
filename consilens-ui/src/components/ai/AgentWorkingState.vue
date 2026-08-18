<script setup>
import { h, computed } from "vue";
import { NTag, NEmpty } from "naive-ui";

// 只读展示当前草稿（源/目标/任务）与阶段；ID 一律按字符串展示；
// 字段名以后端 workingState 为准，此处按通用结构递归渲染，未知字段不崩溃。
const props = defineProps({
  workingState: { type: Object, default: () => ({}) },
  status: { type: String, default: "" },
});

const STAGE_LABELS = {
  DISCOVERY: "目标识别",
  COLLECTING_DATASOURCES: "收集数据源信息",
  VALIDATING_CONNECTIONS: "验证连接",
  DISCOVERING_METADATA: "读取元数据",
  DRAFTING_COMPARISON: "生成比对草稿",
  PLAN_READY: "计划就绪",
  RESOURCES_READY: "资源已就绪",
  COMPLETED: "已完成",
};

function stageLabel(stage) {
  return STAGE_LABELS[stage] || stage || "-";
}

function isIdKey(key) {
  return /id$/i.test(key) || /^id$/i.test(key);
}

function friendlyLabel(key) {
  // camelCase -> 空格分隔的中文友好显示（仅展示用）
  return key
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/_/g, " ");
}

function renderValue(key, value) {
  if (value === null || value === undefined || value === "") {
    return h("span", { class: "aws-muted" }, "-");
  }
  if (typeof value === "object") {
    if (Array.isArray(value)) {
      return value.length
        ? h("div", { class: "aws-list" }, value.map((item, index) => renderArrayItem(item, index)))
        : h("span", { class: "aws-muted" }, "空列表");
    }
    return h("div", { class: "aws-nested" }, renderObject(value));
  }
  if (typeof value === "boolean") {
    return h("span", { class: value ? "aws-ok" : "aws-muted" }, value ? "是" : "否");
  }
  const text = isIdKey(key) ? String(value) : String(value);
  return h("span", { class: isIdKey(key) ? "aws-id" : "aws-value" }, text);
}

function renderArrayItem(item, index) {
  if (item !== null && typeof item === "object") {
    return h("div", { class: "aws-list-item" }, renderObject(item));
  }
  return h("div", { class: "aws-list-item" }, String(item));
}

function renderObject(obj) {
  const children = [];
  for (const [key, value] of Object.entries(obj)) {
    children.push(
      h("div", { class: "aws-row", key }, [
        h("span", { class: "aws-key" }, friendlyLabel(key)),
        renderValue(key, value),
      ])
    );
  }
  return children;
}

const sections = computed(() => {
  const entries = Object.entries(props.workingState || {});
  const stage = props.workingState?.stage;
  const rest = entries.filter(([key]) => key !== "stage");
  return { stage, rest };
});

const isEmpty = computed(() => !Object.keys(props.workingState || {}).length);
</script>

<template>
  <div class="working-state">
    <template v-if="isEmpty">
      <n-empty description="暂无工作状态" size="small" />
    </template>
    <template v-else>
      <div class="aws-stage">
        <span class="aws-stage-label">当前阶段</span>
        <n-tag size="small" :bordered="false" type="info" round>{{ stageLabel(sections.stage) }}</n-tag>
      </div>
      <div class="aws-body">
        <template v-for="[key, value] in sections.rest" :key="key">
          <div v-if="value && typeof value === 'object' && !Array.isArray(value)" class="aws-section">
            <div class="aws-section-title">{{ friendlyLabel(key) }}</div>
            <div class="aws-section-body">
              <template v-for="(child, childKey) in value" :key="childKey">
                <div class="aws-row">
                  <span class="aws-key">{{ friendlyLabel(childKey) }}</span>
                  <component :is="() => renderValue(childKey, child)" />
                </div>
              </template>
            </div>
          </div>
          <div v-else class="aws-row">
            <span class="aws-key">{{ friendlyLabel(key) }}</span>
            <component :is="() => renderValue(key, value)" />
          </div>
        </template>
      </div>
    </template>
  </div>
</template>

<style lang="scss">
// 注意：renderValue 使用 h() 生成节点，不带 scoped 属性，因此本组件样式不加 scoped，
// 类名统一加 aws- 前缀避免污染全局。
.working-state {
  padding: 4px 2px;

  .aws-stage {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--border);
  }

  .aws-stage-label {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
  }

  .aws-body {
    padding-top: 4px;
  }

  .aws-section {
    margin-top: 12px;
  }

  .aws-section-title {
    font-size: 12px;
    font-weight: 600;
    color: var(--text-secondary);
    margin-bottom: 6px;
  }

  .aws-section-body {
    padding-left: 8px;
    border-left: 2px solid var(--border);
  }

  .aws-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    padding: 4px 0;
    font-size: 13px;
  }

  .aws-key {
    flex-shrink: 0;
    color: var(--text-secondary);
    font-size: 12px;
  }

  .aws-value {
    color: var(--text);
    text-align: right;
    word-break: break-all;
  }

  .aws-id {
    color: var(--primary);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
    word-break: break-all;
    text-align: right;
  }

  .aws-muted {
    color: var(--text-muted);
    font-size: 12px;
    text-align: right;
  }

  .aws-ok {
    color: var(--success);
  }

  .aws-nested {
    min-width: 0;
    flex: 1;
  }

  .aws-list {
    min-width: 0;
    flex: 1;
    text-align: right;
  }

  .aws-list-item {
    padding: 2px 0;
    font-size: 12px;
    color: var(--text-secondary);
    word-break: break-all;
  }
}
</style>
