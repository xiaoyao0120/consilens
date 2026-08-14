<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, h, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useMessage } from "naive-ui";
import { NButton, NIcon, NPopconfirm, NSpin } from "naive-ui";
import { ArrowBackOutline, RefreshOutline, TimeOutline, ServerOutline, PlayCircleOutline, PulseOutline } from "@vicons/ionicons5";
import { getTaskInstance, retryTask, cancelTask, getArtifactContent, getDiffReport, listArtifactDifferences } from "@/api/modules";
import { formatTime, formatDuration, formatNumber, formatRatio } from "@/utils/format";
import StateTag from "@/components/common/StateTag.vue";
import KeyValueList from "@/components/common/KeyValueList.vue";
import MatchScoreGauge from "@/components/common/MatchScoreGauge.vue";
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

// ===== 差异明细分页 =====
const diffPage = reactive({
  page: 1,
  pageSize: 10,
  total: 0,
  truncated: false,
  items: [],
  loading: false,
  loaded: false,
});

// 操作类型筛选（图例勾选框，多选；默认全选 = 全部类型，取消勾选即只看其余类型）
const FILTER_OPS = [
  { value: "mismatch", label: "不一致" },
  { value: "source_missing", label: "源端缺失" },
  { value: "target_missing", label: "目标缺失" },
];
const diffOps = ref(["mismatch", "source_missing", "target_missing"]);

async function loadDiffPage(page = 1) {
  const runResult = (task.value?.artifacts || []).find((item) => item.artifactType === "RUN_RESULT");
  if (!runResult) return;
  // 全部类型都未勾选时不返回数据
  if (diffOps.value.length === 0) {
    diffPage.items = [];
    diffPage.total = 0;
    diffPage.truncated = false;
    diffPage.page = page;
    diffPage.loaded = true;
    diffPage.loading = false;
    return;
  }
  diffPage.loading = true;
  try {
    const result = await listArtifactDifferences(runResult.artifactId, {
      offset: (page - 1) * diffPage.pageSize,
      limit: diffPage.pageSize,
      operation: diffOps.value.length === 3 ? undefined : diffOps.value.join(","),
    });
    diffPage.items = result.items || [];
    diffPage.total = result.total ?? diffPage.items.length;
    diffPage.truncated = !!result.truncated;
    diffPage.page = page;
    diffPage.loaded = true;
  } catch (error) {
    message.error(error?.response?.data?.error || "获取差异明细失败");
  } finally {
    diffPage.loading = false;
  }
}

function handleDiffOpsChange(ops) {
  diffOps.value = ops;
  diffPage.page = 1;
  // 保留当前数据直到新数据返回（勾选栏与表格不闪空）
  loadDiffPage(1);
}

// 切换勾选并返回新数组（由 handleDiffOpsChange 统一处理）
function toggleOp(value) {
  const cur = [...diffOps.value];
  const index = cur.indexOf(value);
  if (index >= 0) {
    cur.splice(index, 1);
  } else {
    cur.push(value);
  }
  return cur;
}

function handleDiffPageChange(page) {
  loadDiffPage(page);
}

function handleDiffPageSizeChange(pageSize) {
  diffPage.pageSize = pageSize;
  diffPage.page = 1;
  loadDiffPage(1);
}

// 任务成功且 diff 报告可用时加载第一页
watch(
  () => hasDiffData.value,
  (ready) => {
    if (ready && !diffPage.loaded) loadDiffPage(1);
  }
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

          <n-tab-pane name="diff" tab="差异明细" class="diff-pane">
            <n-spin :show="diffReportLoading || diffPage.loading" class="diff-spin">
              <template v-if="hasDiffData">
                <div class="diff-layout">
                  <div class="diff-legend">
                    <span v-for="op in FILTER_OPS" :key="op.value" class="legend-item">
                      <n-checkbox
                        size="small"
                        :checked="diffOps.includes(op.value)"
                        @update:checked="handleDiffOpsChange(toggleOp(op.value))"
                      >
                        {{ op.label }}
                      </n-checkbox>
                    </span>
                    <span class="legend-sep" />
                    <span class="legend-item"><i class="swatch swatch-old" />旧值</span>
                    <span class="legend-item"><i class="swatch swatch-new" />新值</span>
                    <span v-if="diffOps.length === FILTER_OPS.length" class="legend-all">全部类型</span>
                  </div>
                  <div class="diff-table-area">
                    <ValueDiffTable v-if="diffPage.items.length" :samples="diffPage.items" />
                    <EmptyState
                      v-if="diffPage.loaded && !diffPage.items.length"
                      title="未发现差异记录"
                      description="本次比对两表数据完全一致"
                    />
                  </div>
                  <div v-if="diffPage.loaded && diffPage.items.length" class="diff-footer">
                    <n-pagination
                      :page="diffPage.page"
                      :page-size="diffPage.pageSize"
                      :item-count="diffPage.total"
                      :page-sizes="[10, 20, 50]"
                      show-size-picker
                      @update:page="handleDiffPageChange"
                      @update:page-size="handleDiffPageSizeChange"
                    />
                    <span class="text-muted diff-total">共 {{ formatNumber(diffPage.total) }} 条</span>
                  </div>
                </div>
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

.diff-pane {
  height: calc(100vh - 320px);
  min-height: 380px;
}

.diff-spin,
.diff-spin :deep(.n-spin-container),
.diff-spin :deep(.n-spin-content) {
  height: 100%;
}

.diff-layout {
  display: flex;
  flex-direction: column;
  height: 100%;
}

// 勾选筛选栏：独立元素，固定不随表格刷新
.diff-legend {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 2px 10px;
  font-size: 12px;
  color: var(--text-secondary);

  .legend-item {
    display: inline-flex;
    align-items: center;
    gap: 5px;

    :deep(.n-checkbox) {
      font-size: 12px;

      .n-checkbox__label {
        font-size: 12px;
      }
    }
  }

  .legend-sep {
    width: 1px;
    height: 12px;
    background: var(--border, #e4e4e7);
  }

  .swatch {
    display: inline-block;
    width: 14px;
    height: 10px;
    border-radius: 3px;

    &.swatch-old { background: rgba(220, 38, 38, 0.12); border: 1px solid rgba(220, 38, 38, 0.45); }
    &.swatch-new { background: rgba(22, 163, 74, 0.12); border: 1px solid rgba(22, 163, 74, 0.45); }
  }

  .legend-all {
    margin-left: auto;
    font-size: 12px;
    color: var(--text-muted, #a1a1aa);
  }
}

// 表格区域：表格内部滚动（表头固定），外层不再产生滚动条
.diff-table-area {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.diff-footer {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border, #e4e4e7);
  margin-top: 10px;
}

.diff-total {
  font-size: 12px;
  flex-shrink: 0;
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
