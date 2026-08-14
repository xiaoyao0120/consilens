<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, h, computed } from "vue";
import { useRouter } from "vue-router";
import { NButton, NIcon } from "naive-ui";
import { EyeOutline } from "@vicons/ionicons5";
import * as echarts from "echarts/core";
import { PieChart, LineChart } from "echarts/charts";
import { TooltipComponent, LegendComponent, GridComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { getDashboardSummary, listTaskInstances } from "@/api/modules";
import { TASK_STATUS_MAP } from "@/common/statusMapping";
import { formatTime } from "@/utils/format";
import StatCard from "@/components/common/StatCard.vue";
import StateTag from "@/components/common/StateTag.vue";
import DiffSummaryBadge from "@/components/common/DiffSummaryBadge.vue";

echarts.use([PieChart, LineChart, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer]);

const router = useRouter();

const summary = ref(null);
const loading = ref(true);

const recentTasks = reactive({ items: [] });
const recentLoading = ref(false);

const statusChartRef = ref(null);
const trendChartRef = ref(null);
let statusChart = null;
let trendChart = null;

async function loadSummary() {
  try {
    summary.value = await getDashboardSummary();
  } finally {
    loading.value = false;
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
  { title: "提交时间", key: "submitTime", width: 170, render: (row) => h("span", { class: "text-secondary" }, formatTime(row.submitTime)) },
];

function renderStatusChart() {
  if (!statusChartRef.value) return;
  if (!statusChart) {
    statusChart = echarts.init(statusChartRef.value);
  }
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
    legend: { bottom: 0, icon: "circle", itemWidth: 8, itemHeight: 8, textStyle: { fontSize: 12 } },
    series: [
      {
        name: "任务状态",
        type: "pie",
        radius: ["52%", "74%"],
        center: ["50%", "44%"],
        itemStyle: { borderRadius: 6, borderColor: "var(--card)", borderWidth: 2 },
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
  const trend = summary.value?.recent7dTrend || [];
  const dates = trend.map((p) => p.date);
  trendChart.setOption({
    tooltip: { trigger: "axis" },
    legend: { bottom: 0, icon: "circle", itemWidth: 8, itemHeight: 8, textStyle: { fontSize: 12 } },
    grid: { left: 40, right: 16, top: 16, bottom: 40 },
    xAxis: { type: "category", data: dates, axisLabel: { fontSize: 11 } },
    yAxis: { type: "value", minInterval: 1, axisLabel: { fontSize: 11 } },
    series: [
      { name: "任务数", type: "line", smooth: true, data: trend.map((p) => p.taskCount), itemStyle: { color: "#2563eb" } },
      { name: "差异行数", type: "line", smooth: true, data: trend.map((p) => p.differenceCount), itemStyle: { color: "#d97706" } },
    ],
  });
}

function resizeChart() {
  statusChart?.resize();
  trendChart?.resize();
}

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
        :value="summary?.todayTaskCount ?? '-'"
        color="var(--primary)"
        extra="今天提交的任务数"
      />
      <StatCard
        label="运行中任务"
        :value="summary?.runningTaskCount ?? '-'"
        color="var(--success)"
        extra="待调度 / 执行中"
      />
      <StatCard
        label="7 天差异行数"
        :value="summary?.recent7dDifferenceCount ?? '-'"
        color="var(--warning)"
        extra="近 7 天运行结果累计"
      />
      <StatCard
        label="节点在线"
        :value="summary ? `${summary.onlineNodeCount}/${summary.totalNodeCount}` : '-'"
        color="var(--danger)"
        extra="在线 / 全部节点"
      />
    </div>

    <div class="dashboard-grid">
      <n-card :bordered="true" title="状态分布">
        <div ref="statusChartRef" class="chart-box" />
      </n-card>
      <n-card :bordered="true" title="近 7 天趋势">
        <div v-if="!(summary?.recent7dTrend || []).length" class="chart-empty text-muted">
          暂无趋势数据
        </div>
        <div ref="trendChartRef" class="chart-box" />
      </n-card>
    </div>

    <n-card :bordered="true" class="mt-16 recent-card">
      <template #header>
        <div class="flex-bc">
          <span class="card-title">最近任务</span>
          <span class="text-muted">含差异摘要，点击行查看详情</span>
          <n-button text type="primary" size="small" @click="router.push('/instances')">
            查看全部
          </n-button>
        </div>
      </template>
      <n-data-table
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
  gap: 16px;
  margin-bottom: 16px;
  flex-shrink: 0;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  flex-shrink: 0;
}

.recent-card {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.n-card__content) {
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  :deep(.n-data-table) {
    height: 100%;
  }
}

.chart-box {
  height: 190px;
}

.chart-empty {
  position: relative;
  text-align: center;
  margin-top: -200px;
  z-index: 1;
  font-size: 13px;
}

.diff-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
}

.app-name-link {
  color: var(--primary);
  font-weight: 500;
  cursor: pointer;
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}
</style>
