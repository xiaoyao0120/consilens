<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, h, computed, watch, nextTick } from "vue";
import { useRouter } from "vue-router";
import { useAppStore } from "@/store";
import * as echarts from "echarts/core";
import { PieChart, LineChart } from "echarts/charts";
import { TooltipComponent, LegendComponent, GridComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { getDashboardSummary, listTaskInstances } from "@/api/modules";
import { TASK_STATUS_MAP } from "@/common/statusMapping";
import { chartColors as paletteFor } from "@/common/theme";
import { formatNumber } from "@/utils/format";
import StatCard from "@/components/common/StatCard.vue";
import StateTag from "@/components/common/StateTag.vue";
import DiffSummaryBadge from "@/components/common/DiffSummaryBadge.vue";
import EmptyState from "@/components/common/EmptyState.vue";
import SkeletonTable from "@/components/common/SkeletonTable.vue";
import TimeText from "@/components/common/TimeText.vue";
import { TodayOutline, PulseOutline, GitCompareOutline, ServerOutline } from "@vicons/ionicons5";

echarts.use([PieChart, LineChart, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer]);

const router = useRouter();
const appStore = useAppStore();

const summary = ref(null);
const summaryReady = ref(false);

const recentTasks = reactive({ items: [] });
const recentLoading = ref(false);

const statusChartRef = ref(null);
const trendChartRef = ref(null);
let statusChart = null;
let trendChart = null;

// KPI 卡下钻入口
const todayStart = new Date();
todayStart.setHours(0, 0, 0, 0);
const kpiLinks = {
  today: { path: "/instances", query: { startTime: String(todayStart.getTime()) } },
  running: { path: "/instances", query: { status: "RUNNING" } },
  diff: { path: "/instances" },
  nodes: { path: "/nodes" },
};

// 图表空态：无数据时用空态覆盖，避免留白
const statusEmpty = computed(() => !recentLoading.value && recentTasks.items.length === 0);
const trendEmpty = computed(() => {
  if (!summaryReady.value) return false;
  const trend = summary.value?.recent7dTrend || [];
  return trend.length === 0 || trend.every((p) => !p.taskCount && !p.differenceCount);
});

// ECharts 是 canvas 渲染，解析不了 CSS var()：色值统一来自 common/theme（按主题取具体色值）
const chartColors = computed(() => paletteFor(appStore.theme));

async function loadSummary() {
  try {
    summary.value = await getDashboardSummary();
  } finally {
    summaryReady.value = true;
  }
}

async function loadRecentTasks() {
  recentLoading.value = true;
  try {
    const result = await listTaskInstances({ page: 1, pageSize: 5 });
    recentTasks.items = result.items || [];
  } finally {
    recentLoading.value = false;
  }
  renderStatusChart();
}

const recentColumns = [
  {
    title: "任务 ID",
    key: "taskId",
    render: (row) =>
      h(
        "a",
        {
          class: "app-name-link mono",
          style: "white-space: nowrap;",
          onClick: () => router.push(`/instances/${row.id || row.taskId}`),
        },
        row.id || row.taskId
      ),
  },
  { title: "序号", key: "serialNo", width: 190, ellipsis: { tooltip: true }, render: (row) => h("span", { class: "mono text-secondary" }, row.serialNo) },
  {
    title: "状态",
    key: "status",
    width: 90,
    render: (row) => h(StateTag, { status: row.status }),
  },
  {
    title: "差异摘要",
    key: "diffSummary",
    width: 170,
    render: (row) => {
      if (!row.diffSummary) {
        return h("span", { class: "text-muted" }, "-");
      }
      return h(
        "a",
        {
          class: "diff-link",
          title: "查看差异报告",
          onClick: () => router.push({ path: `/instances/${row.id || row.taskId}`, query: { tab: "diff" } }),
        },
        h(DiffSummaryBadge, { summary: row.diffSummary })
      );
    },
  },
  { title: "提交时间", key: "submitTime", width: 170, render: (row) => h(TimeText, { value: row.submitTime }) },
];

function renderStatusChart() {
  if (!statusChartRef.value) return;
  if (!statusChart) {
    statusChart = echarts.init(statusChartRef.value);
  }
  const c = chartColors.value;
  const counts = {};
  recentTasks.items.forEach((item) => {
    counts[item.status] = (counts[item.status] || 0) + 1;
  });
  const data = Object.entries(counts).map(([status, count]) => ({
    name: TASK_STATUS_MAP[status]?.label || status,
    value: count,
  }));
  if (!data.length) {
    statusChart.clear();
    return;
  }
  statusChart.setOption({
    tooltip: { trigger: "item" },
    legend: { bottom: 0, icon: "circle", itemWidth: 8, itemHeight: 8, textStyle: { fontSize: 12, color: c.text } },
    series: [
      {
        name: "任务状态",
        type: "pie",
        radius: ["52%", "74%"],
        center: ["50%", "44%"],
        itemStyle: { borderRadius: 6, borderColor: c.card, borderWidth: 2 },
        label: { show: false },
        data,
      },
    ],
  });
}

function renderTrendChart() {
  if (!trendChartRef.value) return;
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value);
  }
  const c = chartColors.value;
  const trend = summary.value?.recent7dTrend || [];
  const dates = trend.map((p) => p.date);
  trendChart.setOption({
    tooltip: { trigger: "axis" },
    legend: { bottom: 0, icon: "circle", itemWidth: 8, itemHeight: 8, textStyle: { fontSize: 12, color: c.text } },
    grid: { left: 40, right: 16, top: 16, bottom: 40 },
    xAxis: {
      type: "category",
      data: dates,
      axisLabel: { fontSize: 11, color: c.text },
      axisLine: { lineStyle: { color: c.axisLine } },
      axisTick: { show: false },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      axisLabel: { fontSize: 11, color: c.text },
      splitLine: { lineStyle: { color: c.splitLine } },
    },
    series: [
      { name: "任务数", type: "line", smooth: true, data: trend.map((p) => p.taskCount), itemStyle: { color: c.primary } },
      { name: "差异行数", type: "line", smooth: true, data: trend.map((p) => p.differenceCount), itemStyle: { color: c.amber } },
    ],
  });
}

function resizeChart() {
  statusChart?.resize();
  trendChart?.resize();
}

// 主题切换后图表重新取色
watch(
  () => appStore.theme,
  async () => {
    await nextTick();
    renderStatusChart();
    renderTrendChart();
  }
);

onMounted(() => {
  loadSummary().then(() => {
    renderStatusChart();
    renderTrendChart();
  });
  loadRecentTasks();
  window.addEventListener("resize", resizeChart);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeChart);
  statusChart?.dispose();
  trendChart?.dispose();
});
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">工作台</div>
        <div class="page-desc">Consilens 数据一致性校验控制台 · 数据基于最近任务与聚合统计</div>
      </div>
    </div>

    <div class="stats-grid">
      <StatCard
        label="今日任务"
        :value="summary ? formatNumber(summary.todayTaskCount) : '-'"
        extra="今天提交的任务数"
        :icon="TodayOutline"
        icon-color="var(--primary)"
        clickable
        @click="router.push(kpiLinks.today)"
      />
      <StatCard
        label="运行中任务"
        :value="summary ? formatNumber(summary.runningTaskCount) : '-'"
        extra="待调度 / 执行中"
        :icon="PulseOutline"
        icon-color="var(--violet)"
        clickable
        @click="router.push(kpiLinks.running)"
      />
      <StatCard
        label="7 天差异行数"
        :value="summary ? formatNumber(summary.recent7dDifferenceCount) : '-'"
        extra="近 7 天运行结果累计"
        :icon="GitCompareOutline"
        icon-color="var(--accent-green)"
        clickable
        @click="router.push(kpiLinks.diff)"
      />
      <StatCard
        label="节点在线"
        :value="summary ? `${summary.onlineNodeCount}/${summary.totalNodeCount}` : '-'"
        extra="在线 / 全部节点"
        :icon="ServerOutline"
        icon-color="var(--accent-amber)"
        clickable
        @click="router.push(kpiLinks.nodes)"
      />
    </div>

    <div class="dashboard-grid">
      <n-card size="small" :bordered="true" title="状态分布">
        <div class="chart-wrap">
          <div ref="statusChartRef" class="chart-box" />
          <EmptyState
            v-if="statusEmpty"
            class="chart-empty"
            title="暂无任务数据"
            description="最近任务的状态分布将在这里展示"
          />
        </div>
      </n-card>
      <n-card size="small" :bordered="true" title="近 7 天趋势">
        <div class="chart-wrap">
          <div ref="trendChartRef" class="chart-box" />
          <EmptyState
            v-if="trendEmpty"
            class="chart-empty"
            title="暂无趋势数据"
            description="近 7 天的任务与差异趋势将在这里展示"
          />
        </div>
      </n-card>
    </div>

    <n-card size="small" :bordered="true" class="mt-12 recent-card">
      <template #header>
        <div class="flex-bc flex-1">
          <div class="flex-ac">
            <span class="card-title">最近任务</span>
            <span class="text-muted ml-8 recent-hint">含差异摘要，点击行查看详情</span>
          </div>
          <n-button text type="primary" size="small" @click="router.push('/instances')">
            查看全部
          </n-button>
        </div>
      </template>
      <SkeletonTable v-if="recentLoading && !recentTasks.items.length" :rows="5" />
      <EmptyState
        v-else-if="!recentLoading && !recentTasks.items.length"
        class="flex-1"
        title="暂无任务"
        description="提交任务后可在此查看最近执行情况"
      />
      <n-data-table
        v-else
        :columns="recentColumns"
        :data="recentTasks.items"
        :loading="recentLoading"
        size="small"
        :bordered="false"
        :row-props="(row) => ({ style: 'cursor: pointer', onClick: () => router.push(`/instances/${row.id || row.taskId}`) })"
      />
    </n-card>
  </div>
</template>

<style scoped lang="scss">
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-top: 12px;
  flex-shrink: 0;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 12px;
  flex-shrink: 0;
}

.recent-card {
  margin-top: 12px;
  flex: 1 1 auto;
  min-height: 180px;
  display: flex;
  flex-direction: column;

  :deep(.n-card-content) {
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  :deep(.n-data-table) {
    height: 100%;
  }
}

.recent-hint {
  font-size: 12px;
}

.recent-card .skeleton-table {
  flex: 1;
}

.chart-wrap {
  position: relative;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--card);
  padding: 16px 0;
}

.chart-box {
  height: 190px;
}
</style>
