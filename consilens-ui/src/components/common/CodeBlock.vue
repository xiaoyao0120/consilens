<script setup>
import { computed, ref } from "vue";
import { useMessage } from "naive-ui";

const props = defineProps({
  content: { type: String, default: "" },
  label: { type: String, default: "" },
  maxHeight: { type: Number, default: 360 },
  // >0 时超过行数默认折叠，可展开全部
  collapseLines: { type: Number, default: 0 },
});

const message = useMessage();
const expanded = ref(false);

const lineCount = computed(() => (props.content ? props.content.split("\n").length : 0));
const collapsible = computed(
  () => props.collapseLines > 0 && lineCount.value > props.collapseLines
);
const bodyMaxHeight = computed(() => {
  if (!collapsible.value || expanded.value) return props.maxHeight;
  return Math.min(props.maxHeight, props.collapseLines * 19);
});

async function copyAll() {
  try {
    await navigator.clipboard?.writeText(props.content);
    message.success("已复制");
  } catch {
    // 剪贴板不可用时静默
  }
}
</script>

<template>
  <div class="code-block">
    <div class="code-head">
      <span class="code-label">{{ label }}</span>
      <span v-if="collapsible" class="code-meta">{{ lineCount }} 行</span>
      <div class="code-actions">
        <button v-if="collapsible" type="button" class="code-btn" @click="expanded = !expanded">
          {{ expanded ? "收起" : "展开全部" }}
        </button>
        <button type="button" class="code-btn" @click="copyAll">复制</button>
      </div>
    </div>
    <pre class="code-body" :style="{ maxHeight: bodyMaxHeight + 'px' }">{{ content }}</pre>
    <div v-if="collapsible && !expanded" class="code-foot" @click="expanded = true">
      显示全部 {{ lineCount }} 行
    </div>
  </div>
</template>

<style scoped lang="scss">
.code-block {
  border: 1px solid var(--code-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--code-bg);
}

.code-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 14px;
  background: var(--code-chrome);
  border-bottom: 1px solid var(--code-border);
}

.code-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--code-text);
}

.code-meta {
  font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: var(--code-muted);
}

.code-actions {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

.code-btn {
  padding: 2px 8px;
  font-size: 11px;
  color: var(--code-muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 5px;
  cursor: pointer;

  &:hover {
    color: var(--code-text);
    border-color: var(--code-border);
  }
}

.code-body {
  margin: 0;
  padding: 14px;
  overflow: auto;
  font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--code-text);
  white-space: pre;
}

.code-foot {
  padding: 8px 0;
  text-align: center;
  font-size: 12px;
  color: var(--code-muted);
  border-top: 1px solid var(--code-border);
  cursor: pointer;

  &:hover {
    color: var(--code-text);
  }
}
</style>
