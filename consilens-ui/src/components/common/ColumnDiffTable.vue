<script setup>
import { h } from "vue";

const props = defineProps({
  // diff-report.columns（可能为空数组）
  columns: { type: Array, default: () => [] },
});

// 后端 ColumnStat.differencePercentage 已是 0..100 语义（样本占比），直接展示
function formatPct(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return "-";
  return `${Number(value).toFixed(1)}%`;
}

const columns = [
  { title: "列名", key: "name", minWidth: 160 },
  {
    title: "变更行数",
    key: "updateCount",
    width: 110,
    align: "right",
    render: (row) => h("span", { class: "num" }, formatCount(row.updateCount)),
  },
  {
    title: "样本占比",
    key: "percentage",
    minWidth: 200,
    render: (row) =>
      h("div", { class: "pct-cell" }, [
        h("div", { class: "pct-bar-wrap" }, [
          h("div", {
            class: "pct-bar",
            style: {
              width: `${Math.max(0, Math.min(100, row.differencePercentage ?? 0))}%`,
              background: "var(--warning)",
            },
          }),
        ]),
        h("span", { class: "pct-text" }, formatPct(row.differencePercentage)),
      ]),
  },
];

function formatCount(value) {
  if (value === null || value === undefined) return "-";
  return Number(value).toLocaleString("en-US");
}
</script>

<template>
  <div>
    <n-data-table
      :columns="columns"
      :data="props.columns"
      :max-height="420"
      size="small"
      :row-key="(row) => row.name"
    />
    <div class="col-hint text-muted">
      列级统计由差异样本聚合而来（仅覆盖变更行），供快速定位高频率变更列
    </div>
  </div>
</template>

<style scoped lang="scss">
.num {
  font-variant-numeric: tabular-nums;
}

.pct-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pct-bar-wrap {
  flex: 1;
  max-width: 160px;
  height: 6px;
  border-radius: 3px;
  background: var(--border);
  overflow: hidden;
}

.pct-bar {
  height: 100%;
  border-radius: 3px;
}

.pct-text {
  min-width: 48px;
  font-size: 12px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.col-hint {
  margin-top: 8px;
  font-size: 12px;
}
</style>
