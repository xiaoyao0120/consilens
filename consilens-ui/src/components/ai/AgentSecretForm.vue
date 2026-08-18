<script setup>
import { ref, computed, onBeforeUnmount } from "vue";
import { NInput, NButton, NIcon, NAlert } from "naive-ui";
import { LockClosedOutline } from "@vicons/ionicons5";
import { submitAiSecrets } from "@/api/agent";
import { normalizeSecretFields } from "@/stores/agentConversation";
import { formatTime } from "@/utils/format";

// 安全表单：敏感值只存在于组件内部 ref，提交走独立 secrets API；
// 提交成功 / 连续失败超过上限 / 取消 / unmount 时立即清空，不进入 store / localStorage / URL。
const props = defineProps({
  sessionId: { type: String, required: true },
  secretRequestId: { type: String, required: true },
  fields: { type: Array, default: () => [] },
  expiresAt: { type: [String, Number], default: "" },
});

const emit = defineEmits(["submitted"]);

const MAX_FAILS = 3;
const values = ref({});
const calling = ref(false);
const submitted = ref(false);
const failCount = ref(0);
const errorText = ref("");

const normalizedFields = computed(() => normalizeSecretFields(props.fields));

function clearValues() {
  for (const key of Object.keys(values.value)) {
    values.value[key] = "";
  }
}

function fieldValue(field) {
  const raw = values.value[field.field];
  return raw === undefined || raw === null ? "" : raw;
}

async function handleSubmit() {
  const missing = normalizedFields.value
    .filter((field) => field.required && !fieldValue(field).trim())
    .map((field) => field.title);
  if (missing.length) {
    errorText.value = `请填写：${missing.join("、")}`;
    return;
  }
  calling.value = true;
  errorText.value = "";
  try {
    const payload = {};
    for (const field of normalizedFields.value) {
      payload[field.field] = fieldValue(field);
    }
    await submitAiSecrets(props.sessionId, {
      secretRequestId: props.secretRequestId,
      values: payload,
    });
    submitted.value = true;
    failCount.value = 0;
    clearValues(); // 提交成功立即清空
    emit("submitted", { secretRequestId: props.secretRequestId });
  } catch (err) {
    failCount.value += 1;
    if (failCount.value >= MAX_FAILS) {
      clearValues(); // 失败超过上限立即清空并停止输入
      errorText.value = `多次提交失败，凭据已清空，请等待 Agent 重新发起安全表单（${err?.message || "提交失败"}）`;
    } else {
      errorText.value = err?.message || "提交失败，请重试";
    }
  } finally {
    calling.value = false;
  }
}

// 取消（父级移除表单）与组件卸载时清空
function handleCancel() {
  clearValues();
  emit("submitted", { secretRequestId: props.secretRequestId, cancelled: true });
}

onBeforeUnmount(clearValues);
</script>

<template>
  <div class="secret-form">
    <div class="secret-head">
      <n-icon :size="15" color="var(--primary)">
        <LockClosedOutline />
      </n-icon>
      <span class="secret-title">安全凭据提交</span>
      <span v-if="expiresAt" class="secret-expires">有效期至 {{ formatTime(expiresAt) }}</span>
    </div>

    <div v-if="submitted" class="secret-done">
      凭据已加密提交，正在等待 Agent 继续处理…
    </div>

    <template v-else>
      <div class="secret-hint">凭据仅通过加密通道提交，不会出现在对话记录中，提交后立即清空。</div>
      <div class="secret-fields">
        <div v-for="field in normalizedFields" :key="field.field" class="secret-field">
          <label class="field-label">
            {{ field.title }}
            <span v-if="field.required" class="required-mark">*</span>
          </label>
          <n-input
            v-model:value="values[field.field]"
            :type="field.type === 'textarea' ? 'textarea' : 'password'"
            :rows="field.rows"
            :autosize="field.type === 'textarea' ? { minRows: field.rows, maxRows: 8 } : undefined"
            :placeholder="field.placeholder"
            show-password-on="click"
            :disabled="calling"
          />
        </div>
      </div>
      <n-alert v-if="errorText" type="error" :bordered="false" class="mt-8">
        {{ errorText }}
      </n-alert>
      <div class="secret-actions">
        <n-button size="small" :disabled="calling" @click="handleCancel">取消</n-button>
        <n-button size="small" type="primary" :loading="calling" @click="handleSubmit">提交凭据</n-button>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.secret-form {
  padding: 12px 14px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;

  .secret-head {
    display: flex;
    align-items: center;
    gap: 7px;
  }

  .secret-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
  }

  .secret-expires {
    margin-left: auto;
    font-size: 12px;
    color: var(--text-muted);
  }

  .secret-hint {
    margin-top: 6px;
    font-size: 12px;
    color: var(--text-secondary);
  }

  .secret-fields {
    margin-top: 12px;
  }

  .secret-field {
    margin-bottom: 12px;
  }

  .field-label {
    display: block;
    margin-bottom: 6px;
    font-size: 13px;
    font-weight: 500;
    color: var(--text);
  }

  .required-mark {
    color: var(--danger);
  }

  .secret-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 4px;
  }

  .secret-done {
    margin-top: 8px;
    font-size: 13px;
    color: var(--success);
  }
}
</style>
