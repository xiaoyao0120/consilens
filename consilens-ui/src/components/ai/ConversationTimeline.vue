<script setup>
import { ref, watch, nextTick } from "vue";
import { NIcon, NTag, NEmpty } from "naive-ui";
import {
  PersonOutline,
  SparklesOutline,
  HelpCircleOutline,
  LinkOutline,
  TimeOutline,
} from "@vicons/ionicons5";
import AgentToolStatus from "./AgentToolStatus.vue";
import AgentSecretForm from "./AgentSecretForm.vue";
import AgentApprovalCard from "./AgentApprovalCard.vue";
import { formatTime } from "@/utils/format";

const props = defineProps({
  events: { type: Array, default: () => [] },
  pendingDelta: { type: String, default: "" },
  sessionId: { type: String, default: "" },
  isRunning: { type: Boolean, default: false },
});

const listRef = ref(null);

function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}

watch(
  () => [props.events.length, props.pendingDelta],
  () => scrollToBottom(),
  { flush: "post" }
);

// ===== 事件渲染辅助 =====

const RUN_LABELS = {
  RUN_STARTED: "运行开始",
  RUN_SUSPENDED: "运行暂停",
  RUN_COMPLETED: "运行完成",
  RUN_FAILED: "运行失败",
  RUN_CANCELLED: "运行已取消",
  TURN_STARTED: "轮次开始",
  STEERING_QUEUED: "补充指令已加入",
};

function toolStatusOf(event) {
  const payload = event.payload || {};
  switch (event.type) {
    case "TOOL_STARTED":
      return { status: "started", toolName: payload.toolName, label: payload.label, message: "" };
    case "TOOL_PROGRESS":
      return {
        status: "progress",
        toolName: payload.toolName,
        label: payload.label,
        message: payload.message,
        percent: payload.percent,
      };
    case "TOOL_COMPLETED":
      return {
        status: payload.status === "FAILED" || payload.status === "ERROR" ? "failed" : "completed",
        toolName: payload.toolName,
        label: payload.label,
        message: payload.safeSummary || payload.summary || payload.message,
      };
    case "TOOL_BLOCKED":
      return {
        status: "blocked",
        toolName: payload.toolName,
        label: payload.label,
        message: payload.safeMessage || payload.errorCode,
      };
    default:
      return null;
  }
}

function resourceLink(payload) {
  const type = String(payload.resourceType || "").toUpperCase();
  const id = String(payload.resourceId || "");
  if (type.includes("DATASOURCE")) return { path: "/datasources", label: "数据源" };
  if (type.includes("TASK_DEFINITION") || type.includes("DEFINITION")) {
    return { path: `/definitions/${id}`, label: "任务定义" };
  }
  if (type.includes("INSTANCE")) return { path: `/instances/${id}`, label: "运行实例" };
  return null;
}

function questionText(event) {
  const payload = event.payload || {};
  return payload.question || "Agent 需要补充信息";
}

function auditText(event) {
  const payload = event.payload || {};
  const label = RUN_LABELS[event.type];
  if (event.type === "RUN_SUSPENDED") {
    const reason = payload.reason || "等待用户处理";
    const reasonLabel =
      { WAITING_SECRET: "等待凭据", WAITING_APPROVAL: "等待审批", WAITING_INPUT: "等待输入" }[reason] || reason;
    return `${label}：${payload.message || reasonLabel}`;
  }
  if (event.type === "RUN_FAILED") {
    return `${label}：${payload.safeMessage || payload.errorCode || ""}`.trim();
  }
  return label || `事件 ${event.type}`;
}

function isToolEvent(type) {
  return ["TOOL_STARTED", "TOOL_PROGRESS", "TOOL_COMPLETED", "TOOL_BLOCKED"].includes(type);
}

function isAuditEvent(type) {
  return [
    "RUN_STARTED",
    "RUN_SUSPENDED",
    "RUN_COMPLETED",
    "RUN_FAILED",
    "RUN_CANCELLED",
    "TURN_STARTED",
    "STEERING_QUEUED",
  ].includes(type);
}
</script>

<template>
  <div ref="listRef" class="timeline-scroll">
    <div v-if="!events.length" class="timeline-empty">
      <n-empty description="还没有消息，描述你的目标开始对话" size="small" />
    </div>

    <div v-for="event in events" :key="event.seq" class="timeline-item">
      <!-- 用户消息 -->
      <div v-if="event.type === 'USER_MESSAGE'" class="msg-row user-row">
        <div class="msg-bubble user-bubble">
          <div class="msg-text">{{ event.payload?.text || "" }}</div>
          <div class="msg-meta">
            <n-icon :size="11"><PersonOutline /></n-icon>
            <span>你 · {{ formatTime(event.createdAt) }}</span>
          </div>
        </div>
      </div>

      <!-- 助手消息 -->
      <div v-else-if="event.type === 'ASSISTANT_MESSAGE'" class="msg-row">
        <div class="msg-avatar">
          <n-icon :size="15" color="var(--primary)"><SparklesOutline /></n-icon>
        </div>
        <div class="msg-bubble assistant-bubble">
          <div class="msg-text">{{ event.payload?.text || "" }}</div>
          <div class="msg-meta">
            <span>助手 · {{ formatTime(event.createdAt) }}</span>
          </div>
        </div>
      </div>

      <!-- 工具状态 -->
      <div v-else-if="isToolEvent(event.type)" class="msg-row compact-row">
        <div class="msg-side" />
        <div class="msg-body">
          <agent-tool-status v-bind="toolStatusOf(event)" />
        </div>
      </div>

      <!-- 需要用户回答问题 -->
      <div v-else-if="event.type === 'QUESTION_REQUIRED'" class="msg-row compact-row">
        <div class="msg-side">
          <n-icon :size="15" color="var(--primary)"><HelpCircleOutline /></n-icon>
        </div>
        <div class="msg-body">
          <div class="info-card">
            <div class="info-card-title">需要补充信息</div>
            <div class="info-card-text">{{ questionText(event) }}</div>
            <div v-if="event.payload?.missingSlots?.length" class="info-card-slots">
              <n-tag
                v-for="slot in event.payload.missingSlots"
                :key="slot"
                size="small"
                :bordered="false"
                round
              >
                {{ slot }}
              </n-tag>
            </div>
          </div>
        </div>
      </div>

      <!-- 安全凭据表单 -->
      <div v-else-if="event.type === 'SECRET_INPUT_REQUIRED'" class="msg-row compact-row">
        <div class="msg-side" />
        <div class="msg-body">
          <agent-secret-form
            :key="String(event.payload?.secretRequestId || event.seq)"
            :session-id="sessionId"
            :secret-request-id="String(event.payload?.secretRequestId || '')"
            :fields="event.payload?.fields || []"
            :expires-at="event.payload?.expiresAt"
          />
        </div>
      </div>

      <!-- 审批卡 -->
      <div v-else-if="event.type === 'APPROVAL_REQUIRED'" class="msg-row compact-row">
        <div class="msg-side" />
        <div class="msg-body">
          <agent-approval-card
            :key="String(event.payload?.approvalId || event.seq)"
            :approval="event.payload || {}"
          />
        </div>
      </div>

      <!-- 资源创建成功 -->
      <div v-else-if="event.type === 'RESOURCE_CREATED'" class="msg-row compact-row">
        <div class="msg-side">
          <n-icon :size="15" color="var(--success)"><LinkOutline /></n-icon>
        </div>
        <div class="msg-body">
          <div class="info-card resource-card">
            <div class="info-card-title">资源已创建</div>
            <div class="info-card-text">
              {{ event.payload?.name || "资源" }}
              <span v-if="event.payload?.resourceType" class="text-secondary">
                （{{ event.payload.resourceType }}）
              </span>
            </div>
            <a
              v-if="resourceLink(event.payload)"
              class="resource-link"
              :href="`#${resourceLink(event.payload).path}`"
            >
              查看{{ resourceLink(event.payload).label }} →
            </a>
            <div v-else class="info-card-slots">
              <span class="resource-id">ID：{{ String(event.payload?.resourceId || "") }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 运行状态行 -->
      <div v-else-if="isAuditEvent(event.type)" class="msg-row audit-row">
        <n-icon :size="12" class="audit-icon"><TimeOutline /></n-icon>
        <span class="audit-text">{{ auditText(event) }}</span>
        <span class="audit-time">{{ formatTime(event.createdAt) }}</span>
      </div>

      <!-- 未知事件：通用审计项，不崩溃 -->
      <div v-else class="msg-row audit-row">
        <span class="audit-text">事件 {{ event.type }}</span>
        <span class="audit-time">{{ formatTime(event.createdAt) }}</span>
      </div>
    </div>

    <!-- 流式增量缓存 -->
    <div v-if="pendingDelta" class="msg-row">
      <div class="msg-avatar">
        <n-icon :size="15" color="var(--primary)"><SparklesOutline /></n-icon>
      </div>
      <div class="msg-bubble assistant-bubble">
        <div class="msg-text">{{ pendingDelta }}</div>
        <div class="msg-meta">
          <span>助手正在输入…</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.timeline-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}

.timeline-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 200px;
}

.timeline-item {
  margin-bottom: 12px;
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;

  &.compact-row {
    align-items: flex-start;
  }

  &.audit-row {
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 2px 0;
  }

  &.user-row {
    justify-content: flex-end;
  }
}

.msg-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: rgba(37, 99, 235, 0.08);
  flex-shrink: 0;
}

.msg-side {
  width: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding-top: 6px;
}

.msg-body {
  flex: 1;
  min-width: 0;
}

.msg-bubble {
  max-width: 78%;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.assistant-bubble {
  background: var(--card);
  border: 1px solid var(--border);
  color: var(--text);
}

.user-bubble {
  background: var(--primary);
  color: #fff;
}

.msg-text {
  white-space: pre-wrap;
}

.msg-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 6px;
  font-size: 11px;
  opacity: 0.75;
}

.info-card {
  padding: 12px 14px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 6px;
}

.info-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  margin-bottom: 6px;
}

.info-card-text {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.info-card-slots {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.resource-card {
  border-left: 3px solid var(--success);
}

.resource-link {
  display: inline-block;
  margin-top: 8px;
  font-size: 13px;
  color: var(--primary);
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}

.resource-id {
  font-size: 12px;
  color: var(--text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.audit-icon {
  color: var(--text-muted);
}

.audit-text {
  font-size: 12px;
  color: var(--text-muted);
}

.audit-time {
  font-size: 11px;
  color: var(--text-muted);
  opacity: 0.7;
}
</style>
