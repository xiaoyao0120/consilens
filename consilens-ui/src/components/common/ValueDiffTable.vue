<script setup>
import { computed, h } from "vue";
import StateTag from "./StateTag.vue";
import { parsePrimaryKey } from "@/utils/format";

const props = defineProps({
  // diff-report.samples：[{operation, primaryKey, metadata, changedColumns}]
  samples: { type: Array, default: () => [] },
});

const columns = [
  {
    title: "操作",
    key: "operation",
    width: 90,
    render: (row) => h(StateTag, { status: row.operation }),
  },
  { title: "主键", key: "primaryKey", minWidth: 180 },
  {
    title: "变更字段",
    key: "changedColumns",
    minWidth: 200,
    render: (row) => {
      const list = row.changedColumns || [];
      if (!list.length) return h("span", { class: "text-muted" }, "-");
      return h(
        "div",
        { class: "col-tags" },
        list.map((name) =>
          h(
            "span",
            { class: "col-tag" },
            name
          )
        )
      );
    },
  },
  {
    title: "详情",
    key: "detail",
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: (row) => h("span", { class: "detail-text" }, row.detail),
  },
];

const rows = computed(() =>
  props.samples.map((sample, index) => ({
    key: index,
    operation: sample.operation || "-",
    primaryKey: parsePrimaryKey(sample.primaryKey),
    changedColumns: sample.changedColumns || [],
    detail: summarize(sample),
  }))
);

function summarize(sample) {
  const metadata = sample.metadata;
  if (!metadata || typeof metadata !== "object") return "-";
  const parts = [];
  if (metadata.changedColumnIndices) {
    parts.push(`变更列下标: ${String(metadata.changedColumnIndices)}`);
  }
  for (const [key, value] of Object.entries(metadata)) {
    if (key.startsWith("changedColumn")) continue;
    const text = typeof value === "object" ? JSON.stringify(value) : String(value);
    if (text.length <= 80) parts.push(`${key}=${text}`);
  }
  return parts.length ? parts.join(" / ") : "-";
}
</script>

<template>
  <n-data-table
    :columns="columns"
    :data="rows"
    :max-height="480"
    size="small"
    :row-key="(row) => row.key"
  />
</template>

<style scoped lang="scss">
.col-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.col-tag {
  padding: 1px 8px;
  font-size: 12px;
  line-height: 18px;
  border-radius: 4px;
  background: var(--warning-soft, rgba(240, 160, 32, 0.12));
  color: var(--warning, #f0a020);
  white-space: nowrap;
}

.detail-text {
  font-size: 12px;
  color: var(--text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
