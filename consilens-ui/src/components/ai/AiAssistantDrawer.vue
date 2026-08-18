<script setup>
import { ref, watch, computed, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { NDrawer, NButton, NIcon, NTag } from "naive-ui";
import { AddOutline, OpenOutline } from "@vicons/ionicons5";
import { useAgentConversationStore } from "@/stores/agentConversation";
import ConversationTimeline from "./ConversationTimeline.vue";
import AgentComposer from "./AgentComposer.vue";

// 业务页侧边栏版 AI 助手：与独立页面共享同一套会话（Pinia store），
// 只承担轻对话；进入需要凭据/审批的写流程时提示跳转完整 AI 助手页。
const props = defineProps({
  show: { type: Boolean, default: false },
});
const emit = defineEmits(["update:show"]);

const router = useRouter();
const store = useAgentConversationStore();

// 抽屉宽度与 AI 助手页聊天框一致（约视口 40%，最小 520）
const drawerWidth = ref(Math.max(520, Math.round(window.innerWidth * 0.4)));
function updateWidth() {
  drawerWidth.value = Math.max(520, Math.round(window.innerWidth * 0.4));
}
onMounted(() => window.addEventListener("resize", updateWidth));
onBeforeUnmount(() => window.removeEventListener("resize", updateWidth));

// 打开抽屉时确保有会话可对话：复用当前会话，否则恢复最近会话，再否则新建
watch(
  () => props.show,
  async (show) => {
    if (!show) return;
    store.initFromStorage();
    if (store.currentSessionId) {
      store.startStream();
      return;
    }
    const last = store.sessionIndex[0]?.id;
    if (last) {
      await store.resumeSession(last).catch(() => {});
    } else {
      await store.createSession("AI 辅助").catch(() => {});
    }
  }
);

const STATUS_META = {
  READY: { label: "就绪", type: "default" },
  RUNNING: { label: "运行中", type: "success" },
  WAITING_SECRET: { label: "等待凭据", type: "warning" },
  WAITING_APPROVAL: { label: "等待审批", type: "warning" },
  WAITING_INPUT: { label: "等待输入", type: "warning" },
  SUSPENDED: { label: "已暂停", type: "warning" },
  COMPLETED: { label: "已完成", type: "success" },
  FAILED: { label: "失败", type: "error" },
  CANCELLED: { label: "已取消", type: "default" },
};

const statusMeta = computed(() => {
  const status = store.session?.status || "";
  return STATUS_META[status] || { label: status || "未开始", type: "default" };
});

// 需要完整界面（凭据表单/审批卡片）的流程状态
const NEEDS_FULL_PAGE = new Set(["WAITING_SECRET", "WAITING_APPROVAL"]);
const gotoBanner = computed(() => NEEDS_FULL_PAGE.has(store.session?.status || ""));

async function handleSend(text) {
  try {
    await store.sendMessage(text);
  } catch (err) {
    if (err?.errorCode === "SESSION_VERSION_CONFLICT") {
      await store.refreshSession();
    }
  }
}

async function handleStop() {
  try {
    await store.cancelRun();
  } catch {
    // 由拦截器统一提示
  }
}

async function handleNew() {
  // 当前会话还没有任何内容时不新建会话，避免产生空会话
  if (store.currentSessionId && !store.orderedEvents.length) return;
  await store.createSession("AI 辅助").catch(() => {});
}

function gotoAssistant() {
  router.push("/ai");
}
</script>

<template>
  <n-drawer :show="show" :width="drawerWidth" placement="right" :trap-focus="false"
    @update:show="emit('update:show', $event)">
    <div class="ai-drawer">
      <div class="drawer-head">
        <div class="drawer-title">
          <span class="drawer-title-text">AI 助手</span>
          <n-tag size="small" :bordered="false" :type="statusMeta.type" round>
            {{ statusMeta.label }}
          </n-tag>
        </div>
        <div class="drawer-actions">
          <n-button size="small" quaternary @click="handleNew">
            <template #icon>
              <n-icon><AddOutline /></n-icon>
            </template>
            新对话
          </n-button>
        </div>
      </div>

      <div v-if="gotoBanner" class="goto-banner">
        <span>当前流程需要凭据/审批，建议在完整 AI 助手中操作</span>
        <n-button size="tiny" type="primary" ghost @click="gotoAssistant">
          <template #icon>
            <n-icon><OpenOutline /></n-icon>
          </template>
          去 AI 助手
        </n-button>
      </div>

      <conversation-timeline
        class="drawer-timeline"
        :events="store.orderedEvents"
        :pending-delta="store.pendingDelta"
        :session-id="store.currentSessionId"
        :is-running="store.isRunning"
      />

      <agent-composer
        class="drawer-composer"
        :disabled="!store.currentSessionId"
        :sending="store.sending"
        :is-running="store.isRunning"
        :stream-state="store.streamState"
        @send="handleSend"
        @stop="handleStop"
      />
    </div>
  </n-drawer>
</template>

<style scoped lang="scss">
.ai-drawer {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--card);
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.drawer-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.drawer-title-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.drawer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.goto-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 14px;
  font-size: 12px;
  color: var(--warning);
  background: rgba(250, 173, 20, 0.08);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.drawer-composer {
  flex-shrink: 0;
}
</style>
