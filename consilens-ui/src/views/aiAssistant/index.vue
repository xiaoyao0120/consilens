<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from "vue";
import { NIcon, NButton, NTag, NModal, NInput, NDrawer, NDrawerContent, NEmpty, useDialog } from "naive-ui";
import {
  AddOutline,
  RefreshOutline,
  ChatbubbleEllipsesOutline,
  AlbumsOutline,
  TrashOutline,
} from "@vicons/ionicons5";
import { useAgentConversationStore } from "@/stores/agentConversation";
import ConversationTimeline from "@/components/ai/ConversationTimeline.vue";
import AgentComposer from "@/components/ai/AgentComposer.vue";
import AgentWorkingState from "@/components/ai/AgentWorkingState.vue";
import { formatTime } from "@/utils/format";

const store = useAgentConversationStore();
const dialog = useDialog();

// ===== 新建会话 =====
const createModal = ref(false);
const newTitle = ref("");
const creating = ref(false);
const titleComposing = ref(false);

// 标题框同样要避开 IME 组合期间的回车（确认候选词不是提交）
function handleTitleKeydown(event) {
  if (event.isComposing || titleComposing.value) return;
  if (event.key === "Enter") handleCreate();
}

async function handleCreate() {
  const title = newTitle.value.trim();
  if (!title) return;
  creating.value = true;
  try {
    await store.createSession(title);
    createModal.value = false;
    newTitle.value = "";
  } catch (err) {
    // 创建失败由 axios 拦截器统一提示
  } finally {
    creating.value = false;
  }
}

function openCreateModal() {
  newTitle.value = "";
  createModal.value = true;
}

// ===== 会话切换 / 刷新 =====
async function handleSelectSession(id) {
  if (id === store.currentSessionId) return;
  try {
    await store.resumeSession(id);
  } catch {
    // 恢复失败由拦截器提示
  }
}

function handleDeleteSession(item) {
  dialog.warning({
    title: "删除会话",
    content: `确认删除会话「${item.title}」吗？该操作会同时删除其全部对话记录，不可恢复。`,
    positiveText: "删除",
    negativeText: "取消",
    onPositiveClick: async () => {
      await store.deleteSession(item.id).catch(() => {});
    },
  });
}

async function handleRefresh() {
  if (!store.currentSessionId) return;
  try {
    await store.refreshSession();
  } catch {
    // 由拦截器提示
  }
}

// ===== 消息 =====
async function handleSend(text) {
  try {
    await store.sendMessage(text);
  } catch (err) {
    if (err?.errorCode === "SESSION_VERSION_CONFLICT") {
      await store.refreshSession(); // 本地事件落后，先恢复再提示
    }
  }
}

async function handleStop() {
  try {
    await store.cancelRun();
  } catch {
    // 由拦截器提示
  }
}

// ===== 状态展示 =====
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

const STREAM_LABELS = {
  idle: "",
  connecting: "连接中…",
  open: "已连接",
  reconnecting: "重连中…",
  closed: "连接已断开",
};

const streamLabel = computed(() => STREAM_LABELS[store.streamState] || "");
const streamConnecting = computed(
  () => store.streamState === "connecting" || store.streamState === "reconnecting"
);

// ===== 窄屏：右侧工作状态收进抽屉 =====
const isNarrow = ref(false);
const drawerShow = ref(false);

function updateNarrow() {
  isNarrow.value = window.innerWidth < 1280;
}

onMounted(() => {
  updateNarrow();
  window.addEventListener("resize", updateNarrow);
  store.initFromStorage();
  // 页面恢复：优先恢复当前会话，否则恢复最近一个会话
  const targetId = store.currentSessionId || store.sessionIndex[0]?.id;
  if (targetId) {
    store.resumeSession(targetId).catch(() => {});
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", updateNarrow);
  store.closeStream();
});
</script>

<template>
  <div class="page-container ai-page">
    <div class="page-header">
      <div>
        <div class="page-title">AI 助手</div>
        <div class="page-desc">通过连续对话创建数据源与比对任务，写操作需审批，凭据走安全表单</div>
      </div>
      <div class="header-actions">
        <n-button v-if="isNarrow" quaternary :disabled="!store.currentSessionId" @click="drawerShow = true">
          <template #icon>
            <n-icon><AlbumsOutline /></n-icon>
          </template>
          草稿
        </n-button>
        <n-button quaternary circle title="刷新会话" :disabled="!store.currentSessionId" @click="handleRefresh">
          <template #icon>
            <n-icon><RefreshOutline /></n-icon>
          </template>
        </n-button>
        <n-button type="primary" @click="openCreateModal">
          <template #icon>
            <n-icon><AddOutline /></n-icon>
          </template>
          新建会话
        </n-button>
      </div>
    </div>

    <div class="ai-body">
      <!-- 左侧：会话列表 -->
      <div class="session-panel">
        <div class="session-title">会话</div>
        <div v-if="store.sessionIndex.length" class="session-list">
          <div
            v-for="item in store.sessionIndex"
            :key="item.id"
            class="session-item"
            :class="{ active: item.id === store.currentSessionId }"
            @click="handleSelectSession(item.id)"
          >
            <div class="session-item-title">
              <span class="session-item-name">{{ item.title }}</span>
              <n-button
                size="tiny"
                quaternary
                circle
                class="session-delete"
                title="删除会话"
                @click.stop="handleDeleteSession(item)"
              >
                <n-icon :size="13"><TrashOutline /></n-icon>
              </n-button>
            </div>
            <div class="session-item-meta">
              <n-tag size="tiny" :bordered="false" round>{{ STATUS_META[item.status]?.label || item.status || "-" }}</n-tag>
              <span>{{ formatTime(item.updatedAt, "MM-DD HH:mm") }}</span>
            </div>
          </div>
        </div>
        <div v-else class="session-empty">
          <n-empty description="暂无会话" size="small" />
        </div>
      </div>

      <!-- 中间：对话 -->
      <div class="conversation-card">
        <div class="conversation-head">
          <div class="conversation-title-wrap">
            <n-icon :size="16" color="var(--primary)"><ChatbubbleEllipsesOutline /></n-icon>
            <span class="conversation-title">{{ store.currentSession?.title || "AI 助手" }}</span>
            <n-tag size="small" :bordered="false" :type="statusMeta.type" round>{{ statusMeta.label }}</n-tag>
          </div>
          <div class="conversation-sub">
            <span v-if="streamLabel" class="stream-state" :class="{ connecting: streamConnecting }">
              {{ streamLabel }}
            </span>
            <span v-if="store.lastSeq" class="stream-seq">seq {{ store.lastSeq }}</span>
          </div>
        </div>

        <conversation-timeline
          :events="store.orderedEvents"
          :pending-delta="store.pendingDelta"
          :session-id="store.currentSessionId"
          :is-running="store.isRunning"
        />

        <agent-composer
          :disabled="!store.currentSessionId"
          :sending="store.sending"
          :is-running="store.isRunning"
          :stream-state="store.streamState"
          @send="handleSend"
          @stop="handleStop"
        />
      </div>

      <!-- 右侧：当前草稿 -->
      <div v-if="!isNarrow" class="working-panel">
        <div class="working-title">当前草稿</div>
        <agent-working-state :working-state="store.session?.workingState || {}" :status="store.session?.status || ''" />
      </div>
    </div>

    <!-- 新建会话弹窗 -->
    <n-modal v-model:show="createModal" preset="card" style="width: 440px" title="新建 AI 会话">
      <n-input
        v-model:value="newTitle"
        placeholder="会话标题，例如：orders 对账"
        maxlength="60"
        @keydown="handleTitleKeydown"
        @compositionstart="titleComposing = true"
        @compositionend="titleComposing = false"
      />
      <template #footer>
        <div class="modal-footer">
          <n-button @click="createModal = false">取消</n-button>
          <n-button type="primary" :loading="creating" :disabled="!newTitle.trim()" @click="handleCreate">
            创建
          </n-button>
        </div>
      </template>
    </n-modal>

    <!-- 窄屏工作状态抽屉 -->
    <n-drawer v-model:show="drawerShow" :width="360">
      <n-drawer-content title="当前草稿" closable>
        <agent-working-state :working-state="store.session?.workingState || {}" :status="store.session?.status || ''" />
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped lang="scss">
.ai-page {
  display: flex;
  flex-direction: column;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
}

// ===== 会话列表 =====
.session-panel {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}

.session-title {
  padding: 12px 14px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  border-bottom: 1px solid var(--border);
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  padding: 10px 12px;
  margin-bottom: 6px;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: var(--bg);
  }

  &.active {
    background: rgba(37, 99, 235, 0.06);
    border-color: rgba(37, 99, 235, 0.25);
  }
}

.session-item-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 6px;
}

.session-item-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.session-delete {
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.session-item:hover .session-delete,
.session-item.active .session-delete {
  opacity: 1;
}

.session-item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  font-size: 11px;
  color: var(--text-muted);
}

.session-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

// ===== 对话主区 =====
.conversation-card {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}

.conversation-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}

.conversation-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.conversation-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-sub {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.stream-state {
  font-size: 12px;
  color: var(--success);

  &.connecting {
    color: var(--warning);
  }
}

.stream-seq {
  font-size: 12px;
  color: var(--text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

// ===== 右侧草稿面板 =====
.working-panel {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}

.working-title {
  padding: 12px 14px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  border-bottom: 1px solid var(--border);
}

.working-panel :deep(.working-state) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
