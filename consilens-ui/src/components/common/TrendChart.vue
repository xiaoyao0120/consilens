<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from "vue";
import * as echarts from "echarts/core";
import { LineChart, BarChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

const props = defineProps({
  trend: { type: Array, default: () => [] },
  height: { type: String, default: "280px" },
});

const chartRef = ref(null);
let chart = null;

function renderChart() {
  if (!chartRef.value) return;
  if (!chart) {
    chart = echarts.init(chartRef.value);
  }
  const dates = props.trend.map((item) => item.date);
  const taskCounts = props.trend.map((item) => item.taskCount);
  const diffCounts = props.trend.map((item) => item.differenceCount);
  chart.setOption({
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
    },
    legend: { data: ["任务数", "差异行数"], top: 0 },
    grid: { left: 40, right: 40, top: 36, bottom: 28 },
    xAxis: {
      type: "category",
      data: dates,
      axisLine: { lineStyle: { color: "var(--border-strong)" } },
      axisLabel: { color: "var(--text-secondary)" },
    },
    yAxis: [
      {
        type: "value",
        name: "任务数",
        minInterval: 1,
        splitLine: { lineStyle: { color: "var(--border)" } },
        axisLabel: { color: "var(--text-secondary)" },
      },
      {
        type: "value",
        name: "差异行",
        minInterval: 1,
        splitLine: { show: false },
        axisLabel: { color: "var(--text-secondary)" },
      },
    ],
    series: [
      {
        name: "任务数",
        type: "bar",
        data: taskCounts,
        barMaxWidth: 22,
        itemStyle: { color: "var(--primary)", borderRadius: [4, 4, 0, 0] },
      },
      {
        name: "差异行数",
        type: "line",
        yAxisIndex: 1,
        data: diffCounts,
        smooth: true,
        symbolSize: 6,
        lineStyle: { color: "var(--danger)", width: 2 },
        itemStyle: { color: "var(--danger)" },
      },
    ],
  });
}

function resize() {
  chart?.resize();
}

watch(
  () => props.trend,
  () => renderChart(),
  { deep: true }
);

onMounted(() => {
  renderChart();
  window.addEventListener("resize", resize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  chart?.dispose();
});
</script>

<template>
  <div ref="chartRef" :style="{ height, width: '100%' }" />
</template>
