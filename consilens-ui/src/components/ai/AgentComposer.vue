<script setup>
import { ref } from "vue";
import { NInput, NButton, NIcon, NTooltip } from "naive-ui";
import { SendOutline, StopOutline } from "@vicons/ionicons5";

// 文本输入 / 发送 / 停止；RUNNING 时发送作为 steering（补充指令），按钮文案明确。
const props = defineProps({
  disabled: { type: Boolean, default: false },
  sending: { type: Boolean, default: false },
  isRunning: { type: Boolean, default: false },
  streamState: { type: String, default: "idle" },
});

const emit = defineEmits(["send", "stop"]);
const text = ref("");
// 中文输入法（IME）组合期间按 Enter 是确认候选词，不是发送
const isComposing = ref(false);

function handleSend() {
  const content = text.value.trim();
  if (!content || props.disabled || props.sending) return;
  emit("send", content);
  text.value = "";
}

function handleKeydown(event) {
  // IME 组合中：Enter 由输入法消费，交给浏览器上屏，禁止触发发送
  if (event.isComposing || isComposing.value) return;
  // Enter 发送，Shift+Enter 换行
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    handleSend();
  }
}
</script>

<template>
  <div class="agent-composer">
    <n-input
      v-model:value="text"
      type="textarea"
      :autosize="{ minRows: 2, maxRows: 6 }"
      placeholder="描述你的目标，例如：创建两个数据源并建立比对任务（Enter 发送，Shift+Enter 换行）"
      :disabled="disabled"
      @keydown="handleKeydown"
      @compositionstart="isComposing = true"
      @compositionend="isComposing = false"
    />
    <div class="composer-bar">
      <span v-if="isRunning" class="composer-hint">
        运行中发送将作为补充指令（steering）加入当前轮
      </span>
      <span v-else-if="streamState === 'reconnecting' || streamState === 'connecting'" class="composer-hint">
        正在重连事件流…
      </span>
      <span v-else class="composer-hint" />
      <div class="composer-actions">
        <n-tooltip v-if="isRunning" placement="top">
          <template #trigger>
            <n-button size="small" :disabled="disabled" @click="emit('stop')">
              <template #icon>
                <n-icon><StopOutline /></n-icon>
              </template>
              停止
            </n-button>
          </template>
          取消当前运行（幂等）
        </n-tooltip>
        <n-button
          size="small"
          type="primary"
          :disabled="disabled || !text.trim()"
          :loading="sending"
          @click="handleSend"
        >
          <template #icon>
            <n-icon><SendOutline /></n-icon>
          </template>
          {{ isRunning ? "发送（steering）" : "发送" }}
        </n-button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.agent-composer {
  padding: 12px 14px;
  border-top: 1px solid var(--border);

  .composer-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 8px;
  }

  .composer-hint {
    font-size: 12px;
    color: var(--text-muted);
  }

  .composer-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
  }
}
</style>
