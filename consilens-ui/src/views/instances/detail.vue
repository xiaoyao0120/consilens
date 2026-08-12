<script setup>
import { ref, computed, onMounted, onBeforeUnmount, h, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useMessage } from "naive-ui";
import { NButton, NIcon, NPopconfirm, NSpin } from "naive-ui";
import { ArrowBackOutline, RefreshOutline, TimeOutline, ServerOutline, PlayCircleOutline, PulseOutline } from "@vicons/ionicons5";
import { getTaskInstance, retryTask, cancelTask, getArtifactContent, getDiffReport } from "@/api/modules";
import { formatTime, formatDuration, formatNumber, formatRatio } from "@/utils/format";
import StateTag from "@/components/common/StateTag.vue";
import KeyValueList from "@/components/common/KeyValueList.vue";
import MatchScoreGauge from "@/components/common/MatchScoreGauge.vue";
import SampleNoticeBar from "@/components/common/SampleNoticeBar.vue";
import ValueDiffTable from "@/components/common/ValueDiffTable.vue";
import ColumnDiffTable from "@/components/common/ColumnDiffTable.vue";
import EmptyState from "@/components/common/EmptyState.vue";

const route = useRoute();
const router = useRouter();
const message = useMessage();

const taskId = route.params.taskId;
const loading = ref(true);
const task = ref(null);
let pollTimer = null;

const FINAL_STATUSES = ["SUCCEEDED", "FAILED", "CANCELLED", "RETRYABLE"];
const TABS = ["overview", "diff", "columns"];

function ensureValidTab(tab) {
  return TABS.includes(tab) ? tab : "overview";
}

const activeTab = ref(ensureValidTab(route.query.tab));

async function loadTask(showLoading = false) {
  if (showLoading) loading.value = true;
  try {
    task.value = await getTaskInstance(taskId);
    const status = task.value?.status;
    if (FINAL_STATUSES.includes(status)) stopPolling();
  } catch {
    stopPolling();
  } finally {
    loading.value = false;
  }
}

function startPolling() {
  stopPolling();
  pollTimer = setInterval(() => loadTask(), 2000);
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function handleRetry() {
  await retryTask(taskId);
  message.success("已发起重试");
  loadTask();
}

async function handleCancel() {
  await cancelTask(taskId);
  message.success("已发起取消");
  loadTask();
}

const status = computed(() => task.value?.status || "");
const nextActions = computed(() => task.value?.availableNextActions || []);

// ===== Tab 切换与深链 =====
function handleTabChange(tab) {
  activeTab.value = tab;
  router.replace({ query: { ...route.query, tab } });
  if (tab === "diff" || tab === "columns") loadDiffReport();
}

watch(
  () => route.query.tab,
  (tab) => {
    if (tab) activeTab.value = ensureValidTab(tab);
  }
);

// ===== diff-report =====
const diffReport = ref(null);
const diffReportLoading = ref(false);
let diffReportLoaded = false;

async function loadDiffReport() {
  if (diffReportLoaded || status.value !== "SUCCEEDED") return;
  diffReportLoading.value = true;
  try {
    diffReport.value = await getDiffReport(taskId, { silent: true });
    diffReportLoaded = true;
  } catch {
    diffReport.value = null;
    diffReportLoaded = false;
  } finally {
    diffReportLoading.value = false;
  }
}

watch(
  () => task.value?.status,
  (s) => {
    if (s === "SUCCEEDED") loadDiffReport();
  }
);

const hasDiffData = computed(
  () => diffReport.value !== null && diffReport.value !== undefined && diffReport.value.status !== "NONE"
);

const statistics = computed(() => diffReport.value?.statistics || null);

// ===== 指标 =====
const diffBreakdown = computed(() => {
  const s = statistics.value;
  if (!s) return [];
  return [
    { label: "新增", value: s.sourceMissingCount ?? 0, color: "var(--success)" },
    { label: "删除", value: s.targetMissingCount ?? 0, color: "var(--danger)" },
    { label: "更新", value: s.mismatchCount ?? 0, color: "var(--warning)" },
  ];
});

const diffPercent = computed(() => statistics.value?.differencePercentage ?? null);

const metrics = computed(() => {
  const s = statistics.value;
  if (!s) return [];
  return [
    { label: "源表行数", value: formatNumber(s.sourceRowCount), extra: "source rows" },
    { label: "目标表行数", value: formatNumber(s.targetRowCount), extra: "target rows" },
    { label: "未变化行", value: formatNumber(s.unchangedCount), extra: "unchanged" },
    { label: "比对耗时", value: s.processingTimeMs != null ? `${s.processingTimeMs} ms` : "-", extra: "processing time" },
  ];
});

// ===== 概览：基本信息 =====
const overviewItems = computed(() => [
  { label: "任务 ID", value: task.value?.taskId, copyable: true, mono: true },
  { label: "序号", value: task.value?.serialNo, mono: true },
  { label: "类型", value: task.value?.taskType },
  { label: "Trace ID", value: task.value?.traceId, copyable: true, mono: true },
  { label: "执行节点", value: task.value?.executeNodeKey || "-" },
  { label: "重试次数", value: task.value?.retryCount ?? 0 },
  { label: "提交时间", value: formatTime(task.value?.submitTime) },
  { label: "开始时间", value: formatTime(task.value?.startTime) },
  { label: "结束时间", value: formatTime(task.value?.endTime) },
  { label: "耗时", value: formatDuration(task.value?.startTime, task.value?.endTime) },
]);

const diffNotReady = computed(() => status.value !== "SUCCEEDED");

function copyText(text) {
  navigator.clipboard?.writeText(text);
  message.success("已复制");
}

onMounted(async () => {
  await loadTask(true);
  if (!FINAL_STATUSES.includes(status.value)) startPolling();
  if (status.value === "SUCCEEDED") loadDiffReport();
});

onBeforeUnmount(stopPolling);
</script>

<template>
  <div class="page-container">
    <n-spin :show="loading && !task">
      <template v-if="task">
        <!-- 头部 -->
        <div class="detail-header">
          <div class="header-left">
            <n-button quaternary circle class="back-btn" @click="router.push('/instances')">
              <template #icon>
                <n-icon><ArrowBackOutline /></n-icon>
              </template>
            </n-button>
            <div>
              <div class="title-row">
                <span class="task-id mono">{{ task.taskId }}</span>
                <StateTag :status="status" />
                <span v-if="task.serialNo" class="serial-no mono">{{ task.serialNo }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-item">
                  <n-icon size="13"><TimeOutline /></n-icon>
                  提交于 {{ formatTime(task.submitTime) }}
                </span>
                <span v-if="task.definitionName" class="meta-item">
                  <n-icon size="13"><PlayCircleOutline /></n-icon>
                  <a class="meta-link" @click="router.push(`/definitions/${task.definitionId}`)">{{ task.definitionName }}</a>
                </span>
                <span v-else class="meta-item">
                  <n-icon size="13"><PulseOutline /></n-icon>
                  <span class="text-muted">外部直传</span>
                </span>
                <span v-if="task.executeNodeKey" class="meta-item">
                  <n-icon size="13"><ServerOutline /></n-icon>
                  {{ task.executeNodeKey }}
                </span>
                <span class="meta-item">
                  <n-icon size="13"><RefreshOutline /></n-icon>
                  重试 {{ task.retryCount ?? 0 }} 次
                </span>
              </div>
            </div>
          </div>
          <div class="header-actions">
            <n-button secondary size="small" :loading="loading" @click="loadTask()">
              <template #icon>
                <n-icon><RefreshOutline /></n-icon>
              </template>
              刷新
            </n-button>
            <n-popconfirm
              v-if="nextActions.includes('retry')"
              @positive-click="handleRetry"
              positive-text="确认重试"
              negative-text="取消"
            >
              <template #trigger>
                <n-button size="small" type="warning" secondary>重试</n-button>
              </template>
              确认重试任务 {{ taskId }} 吗？
            </n-popconfirm>
            <n-popconfirm
              v-if="nextActions.includes('cancel')"
              @positive-click="handleCancel"
              positive-text="确认取消"
              negative-text="取消"
            >
              <template #trigger>
                <n-button size="small" type="error" secondary>取消</n-button>
              </template>
              确认取消任务 {{ taskId }} 吗？
            </n-popconfirm>
          </div>
        </div>

        <!-- 运行中横幅 -->
        <div v-if="status === 'RUNNING' || status === 'PENDING' || status === 'CLAIMED'" class="running-banner">
          <n-icon size="16" color="var(--primary)"><RefreshOutline /></n-icon>
          <span>任务正在{{ status === 'PENDING' ? '等待调度' : '执行中' }}，页面每 2 秒自动刷新</span>
        </div>

        <!-- 指标区（执行结果摘要） -->
        <div v-if="hasDiffData" class="metrics-grid">
          <!-- 匹配度 -->
          <div class="metric-card metric-score">
            <div class="metric-label">匹配度</div>
            <div class="score-wrap">
              <MatchScoreGauge :score="diffReport.matchScore" :status="diffReport.status" :size="64" />
            </div>
          </div>

          <!-- 差异记录 -->
          <div class="metric-card">
            <div class="metric-label">差异记录</div>
            <div class="metric-value" :class="{ danger: diffPercent > 0 }">{{ formatNumber(diffReport.totalDifferenceCount ?? 0) }}</div>
            <div class="metric-extra">共 {{ formatNumber(diffReport.sampleSize ?? 0) }} 条样本{{ diffReport.sampleTruncated ? "（已截断）" : "" }}</div>
            <div v-if="diffPercent !== null" class="pct-track">
              <div class="pct-fill" :style="{ width: `${Math.min(100, diffPercent)}%`, background: diffPercent > 5 ? 'var(--danger)' : 'var(--warning)' }" />
            </div>
            <div class="metric-extra">差异占比 {{ formatRatio(diffPercent) }}</div>
          </div>

          <!-- 差异构成（无数据时隐藏） -->
          <div v-if="diffBreakdown.length" class="metric-card">
            <div class="metric-label">差异构成</div>
            <div class="breakdown">
              <div v-for="item in diffBreakdown" :key="item.label" class="breakdown-row">
                <span class="b-dot" :style="{ background: item.color }" />
                <span class="b-label">{{ item.label }}</span>
                <span class="b-value">{{ formatNumber(item.value) }}</span>
              </div>
            </div>
          </div>

          <!-- 常规指标 -->
          <div v-for="m in metrics" :key="m.label" class="metric-card">
            <div class="metric-label">{{ m.label }}</div>
            <div class="metric-value">{{ m.value }}</div>
            <div class="metric-extra">{{ m.extra }}</div>
          </div>
        </div>

        <!-- Tab 面板 -->
        <n-tabs type="line" animated :value="activeTab" @update:value="handleTabChange" class="detail-tabs">
          <n-tab-pane name="overview" tab="概览">
            <n-card :bordered="true" title="基本信息" class="panel-card">
              <KeyValueList :items="overviewItems" :columns="3" />
            </n-card>
          </n-tab-pane>

          <n-tab-pane name="diff" tab="差异明细">
            <n-spin :show="diffReportLoading">
              <template v-if="hasDiffData">
                <SampleNoticeBar
                  :total="diffReport.totalDifferenceCount"
                  :sample-size="diffReport.sampleSize"
                  :truncated="diffReport.sampleTruncated"
                  :has-differences="(diffReport.totalDifferenceCount ?? 0) > 0"
                />
                <ValueDiffTable v-if="(diffReport.samples || []).length" :samples="diffReport.samples" />
                <EmptyState
                  v-else
                  title="未发现差异记录"
                  description="本次比对两表数据完全一致"
                />
              </template>
              <EmptyState
                v-else-if="diffNotReady"
                title="任务尚未完成"
                description="任务执行完成后即可查看差异报告"
              />
              <EmptyState v-else title="暂无差异数据" description="该任务没有可用的差异报告" />
            </n-spin>
          </n-tab-pane>

          <n-tab-pane name="columns" tab="列分析">
            <n-spin :show="diffReportLoading">
              <template v-if="hasDiffData">
                <ColumnDiffTable v-if="(diffReport.columns || []).length" :columns="diffReport.columns" />
                <EmptyState
                  v-else
                  title="暂无列级统计"
                  description="差异样本中未包含变更列信息（仅 MISMATCH 行提供变更列）"
                />
              </template>
              <EmptyState
                v-else-if="diffNotReady"
                title="任务尚未完成"
                description="任务执行完成后即可查看列级分析"
              />
              <EmptyState v-else title="暂无列分析数据" description="该任务没有可用的列级统计" />
            </n-spin>
          </n-tab-pane>
        </n-tabs>
      </template>

      <n-card v-else-if="!loading" :bordered="true">
        <EmptyState title="任务不存在" description="任务可能已被清理" action="返回任务列表" @action="router.push('/instances')" />
      </n-card>
    </n-spin>
  </div>
</template>

<style scoped lang="scss">
// ===== 头部 =====
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  margin-bottom: 16px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: var(--card-shadow);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.back-btn {
  flex-shrink: 0;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.task-id {
  font-size: 18px;
  font-weight: 600;
  color: var(--text);
}

.serial-no {
  font-size: 12px;
  color: var(--text-muted);
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text-secondary);
}

.meta-link {
  color: var(--primary);
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

// ===== 运行中横幅 =====
.running-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--primary);
  background: rgba(37, 99, 235, 0.06);
  border: 1px solid rgba(37, 99, 235, 0.2);
  border-radius: 8px;
}

// ===== 指标网格 =====
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 10px;
  margin-bottom: 16px;
}

.metric-card {
  min-width: 0;
  padding: 8px 12px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: var(--card-shadow);
  min-width: 0;
}

.metric-label {
  font-size: 10px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.metric-value {
  font-size: 16px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--text);
  font-variant-numeric: tabular-nums;

  &.danger {
    color: var(--danger);
  }
}

.metric-extra {
  margin-top: 2px;
  font-size: 10px;
  color: var(--text-muted);
}

.pct-track {
  height: 4px;
  margin-top: 6px;
  border-radius: 2px;
  background: var(--border);
  overflow: hidden;
}

.pct-fill {
  height: 100%;
  border-radius: 2px;
}

.metric-score {
  .score-wrap {
    display: flex;
    align-items: center;
    justify-content: center;
  }
}

.breakdown {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.breakdown-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.b-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.b-label {
  color: var(--text-secondary);
}

.b-value {
  margin-left: auto;
  font-weight: 600;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

// ===== Tab 面板 =====
.detail-tabs {
  :deep(.n-tabs-nav) {
    padding: 0 4px;
  }
}

// ===== 响应式 =====
@media (max-width: 1200px) {
  .metrics-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 768px) {
  .metrics-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
