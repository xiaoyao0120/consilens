<script setup>
import { ref, computed } from "vue";
import { NIcon, NButton, NDataTable, NTag, NAlert } from "naive-ui";
import { ShieldCheckmarkOutline } from "@vicons/ionicons5";
import { useMessage } from "naive-ui";
import { useAgentConversationStore } from "@/stores/agentConversation";
import { formatTime } from "@/utils/format";

// 审批卡：展示 actions 表格、风险、过期时间，批准/拒绝；
// 提交中禁用两个按钮，防止重复提交。
const props = defineProps({
  // { approvalId, actionDigest, summary, actions, expiresAt, version }
  approval: { type: Object, required: true },
});

const message = useMessage();
const store = useAgentConversationStore();
const submitting = ref(false);
const decided = ref(false);

const approvalId = computed(() => String(props.approval?.approvalId || ""));

const actionRows = computed(() => {
  const actions = props.approval?.actions;
  if (!Array.isArray(actions) || !actions.length) return [];
  return actions.map((action, index) => ({
    key: index,
    action: action.label || action.actionType || action.type || `操作 ${index + 1}`,
    detail: action.detail || action.safeSummary || action.summary || "",
  }));
});

const actionColumns = [
  { title: "动作", key: "action", width: 180, ellipsis: { tooltip: true } },
  {
    title: "说明",
    key: "detail",
    ellipsis: { tooltip: true },
    render: (row) => row.detail || "-",
  },
];

async function handleDecide(decision) {
  if (!approvalId.value || submitting.value) return;
  submitting.value = true;
  try {
    await store.decideApproval(approvalId.value, decision, props.approval);
    decided.value = true;
    message.success(decision === "APPROVE" ? "已批准，Agent 将继续执行" : "已拒绝");
  } catch (err) {
    message.error(err?.message || "审批提交失败");
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="approval-card">
    <div class="approval-head">
      <n-icon :size="15" color="var(--warning)">
        <ShieldCheckmarkOutline />
      </n-icon>
      <span class="approval-title">操作审批</span>
      <span v-if="approval.expiresAt" class="approval-expires">过期时间 {{ formatTime(approval.expiresAt) }}</span>
    </div>

    <div v-if="decided" class="approval-done">
      审批结果已提交，正在等待 Agent 继续处理…
    </div>

    <template v-else>
      <div v-if="approval.summary" class="approval-summary">{{ approval.summary }}</div>
      <div v-if="approval.risk" class="approval-risk">
        <n-tag size="small" :bordered="false" type="warning" round>风险提示</n-tag>
        <span class="risk-text">{{ approval.risk }}</span>
      </div>
      <n-data-table
        v-if="actionRows.length"
        :columns="actionColumns"
        :data="actionRows"
        size="small"
        :bordered="false"
        :max-height="220"
        class="approval-table"
      />
      <div v-else class="approval-empty">该审批不包含可展示的动作明细</div>
      <n-alert type="warning" :bordered="false" class="mt-8">
        请核对上述将创建的资源与参数。批准后 Agent 将按计划顺序执行写操作，未批准前不会产生任何写入。
      </n-alert>
      <div class="approval-actions">
        <n-button size="small" type="error" ghost :loading="submitting" :disabled="submitting" @click="handleDecide('REJECT')">
          拒绝
        </n-button>
        <n-button size="small" type="primary" :loading="submitting" :disabled="submitting" @click="handleDecide('APPROVE')">
          批准
        </n-button>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.approval-card {
  padding: 12px 14px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;

  .approval-head {
    display: flex;
    align-items: center;
    gap: 7px;
  }

  .approval-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
  }

  .approval-expires {
    margin-left: auto;
    font-size: 12px;
    color: var(--text-muted);
  }

  .approval-summary {
    margin-top: 8px;
    font-size: 13px;
    color: var(--text);
    white-space: pre-wrap;
    word-break: break-word;
  }

  .approval-risk {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;

    .risk-text {
      font-size: 12px;
      color: var(--warning);
    }
  }

  .approval-table {
    margin-top: 10px;
  }

  .approval-empty {
    margin-top: 10px;
    font-size: 12px;
    color: var(--text-muted);
  }

  .approval-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 10px;
  }

  .approval-done {
    margin-top: 8px;
    font-size: 13px;
    color: var(--success);
  }
}
</style>
