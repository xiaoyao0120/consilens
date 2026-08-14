<script setup>
import { computed, h, ref } from "vue";
import { NButton, NDrawer, NDrawerContent, NDataTable } from "naive-ui";
import StateTag from "./StateTag.vue";
import { parsePrimaryKey } from "@/utils/format";

// 差异明细展示（参考 DataGrip / Redgate Data Compare 等对比工具的配色习惯）：
// - 整行按操作类型着色：更新=琥珀、新增=绿、删除=红（极浅背景）
// - 每个变化字段一列（源端/目标双列），旧值红色、新值绿色，变化单元格带浅色底标记
// - 「详情」抽屉逐字段对比，变化的行红底、源红目标绿
const props = defineProps({
  // [{operation, primaryKey, metadata, changedColumns, columnNames, sourceValues, targetValues}]
  samples: { type: Array, default: () => [] },
  // 占满父容器高度：表格内部滚动、表头固定不随内容滚动
  flexHeight: { type: Boolean, default: true },
});

function formatValue(value) {
  if (value === null || value === undefined) return "";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function opKey(sample) {
  return (sample.operation || "").toLowerCase();
}

function isMismatch(sample) {
  return opKey(sample) === "mismatch";
}

// 展示为列的字段下标：MISMATCH 只列变化字段，增/删行列全部字段
function changedIndices(sample) {
  const names = sample.columnNames || [];
  const source = sample.sourceValues || [];
  const target = sample.targetValues || [];
  if (isMismatch(sample)) {
    const metadata = sample.metadata || {};
    let indices = Array.isArray(metadata.changedColumnIndices) && metadata.changedColumnIndices.length
      ? metadata.changedColumnIndices
      : null;
    if (!indices) {
      indices = [];
      const max = Math.max(names.length, source.length, target.length);
      for (let i = 0; i < max; i++) {
        if (formatValue(source[i]) !== formatValue(target[i])) indices.push(i);
      }
    }
    return indices.filter((i) => i >= 0 && i < names.length);
  }
  return names.map((_, i) => i);
}

// 该行展示用的字段对比（仅变化字段；增/删行单边有值）
function fieldPairs(sample) {
  const names = sample.columnNames || [];
  const source = sample.sourceValues || [];
  const target = sample.targetValues || [];
  return changedIndices(sample).map((i) => ({
    name: names[i],
    source: formatValue(source[i]),
    target: formatValue(target[i]),
    diff: formatValue(source[i]) !== formatValue(target[i]),
  }));
}

// 详情抽屉：全部字段逐项对比
function allFieldPairs(sample) {
  const names = sample.columnNames || [];
  const source = sample.sourceValues || [];
  const target = sample.targetValues || [];
  return names.map((name, i) => ({
    name,
    source: formatValue(source[i]),
    target: formatValue(target[i]),
    diff: formatValue(source[i]) !== formatValue(target[i]),
  }));
}

const rows = computed(() =>
  props.samples.map((sample, index) => ({
    key: index,
    sample,
    operationKey: opKey(sample),
    pairs: fieldPairs(sample),
  }))
);

// 所有行出现过的变化字段（并集，作为表格列）
const changedFieldNames = computed(() => {
  const names = [];
  for (const row of rows.value) {
    for (const pair of row.pairs) {
      if (!names.includes(pair.name)) names.push(pair.name);
    }
  }
  return names;
});

function pairOf(row, name) {
  return row.pairs.find((p) => p.name === name) || null;
}

// 单元格渲染：mismatch 旧值红 / 新值绿；增删行单边着色；空值显示 ∅
function renderCell(row, name, side) {
  const pair = pairOf(row, name);
  if (!pair) return h("span", { class: "cell-muted" }, "-");
  const value = side === "source" ? pair.source : pair.target;
  const empty = value === "";
  const op = row.operationKey;

  if (op === "mismatch") {
    if (!pair.diff) return h("span", { class: "cell-plain" }, empty ? "∅" : value);
    return h(
      "span",
      { class: side === "source" ? "cell-old" : "cell-new" },
      empty ? "∅" : value
    );
  }
  // 新增行展示目标值、删除行展示源值
  const hasValue = op === "source_missing" ? side === "target" : side === "source";
  if (!hasValue) return h("span", { class: "cell-muted" }, "—");
  return h(
    "span",
    { class: op === "source_missing" ? "cell-new" : "cell-old" },
    empty ? "∅" : value
  );
}

const columns = computed(() => {
  const cols = [
    {
      title: "类型",
      key: "operation",
      width: 96,
      fixed: "left",
      render: (row) => h(StateTag, { status: row.sample.operation }),
    },
    {
      title: "主键",
      key: "primaryKey",
      minWidth: 150,
      fixed: "left",
      render: (row) => h("span", { class: "pk-text" }, parsePrimaryKey(row.sample.primaryKey)),
    },
  ];
  for (const name of changedFieldNames.value) {
    cols.push({
      title: name,
      key: `f_${name}`,
      align: "center",
      className: "field-header",
      children: [
        {
          title: "源端",
          key: `src_${name}`,
          width: 180,
          className: "sub-header",
          ellipsis: { tooltip: true },
          render: (row) => renderCell(row, name, "source"),
        },
        {
          title: "目标",
          key: `tgt_${name}`,
          width: 180,
          className: "sub-header",
          ellipsis: { tooltip: true },
          render: (row) => renderCell(row, name, "target"),
        },
      ],
    });
  }
  cols.push({
    title: "操作",
    key: "actions",
    width: 84,
    fixed: "right",
    render: (row) =>
      h(
        NButton,
        { size: "small", text: true, type: "primary", onClick: () => openDetail(row.sample) },
        { default: () => "详情" }
      ),
  });
  return cols;
});

const scrollX = computed(() => 96 + 150 + changedFieldNames.value.length * 360 + 84);

function rowClass(row) {
  if (row.operationKey === "source_missing") return "row-added";
  if (row.operationKey === "target_missing") return "row-removed";
  return "row-updated";
}

// ===== 差异详情抽屉 =====
const drawer = ref({ show: false, rows: [], title: "" });

function openDetail(sample) {
  const pk = parsePrimaryKey(sample.primaryKey);
  const opText = { mismatch: "更新", source_missing: "新增", target_missing: "删除" }[opKey(sample)] || sample.operation;
  drawer.value = {
    show: true,
    rows: allFieldPairs(sample),
    title: `${opText} · 主键 ${pk}`,
  };
}

const detailColumns = [
  { title: "字段", key: "name", width: 200, fixed: "left", render: (row) => h("span", { class: "detail-name" }, row.name) },
  {
    title: "源端",
    key: "source",
    minWidth: 240,
    render: (row) => h("span", { class: row.diff ? "cell-old" : "" }, row.source || "∅"),
  },
  {
    title: "目标",
    key: "target",
    minWidth: 240,
    render: (row) => h("span", { class: row.diff ? "cell-new" : "" }, row.target || "∅"),
  },
];

function detailRowClass(row) {
  return row.diff ? "diff-cell-row" : "";
}
</script>

<template>
  <div class="value-diff-table">
    <n-data-table
      :columns="columns"
      :data="rows"
      :scroll-x="scrollX"
      :flex-height="flexHeight"
      :row-class-name="rowClass"
      size="small"
      :row-key="(row) => row.key"
      :bordered="false"
    />
    <n-drawer v-model:show="drawer.show" :width="760">
      <n-drawer-content :title="drawer.title" closable>
        <n-data-table
          :columns="detailColumns"
          :data="drawer.rows"
          :row-class-name="detailRowClass"
          size="small"
          :bordered="false"
          :max-height="560"
          :row-key="(row) => row.name"
        />
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped lang="scss">
.value-diff-table {
  height: 100%;

  // 表格根占满容器高度，flex-height 模式下 body 内部滚动、表头固定
  :deep(.n-data-table) {
    height: 100%;
  }

  // 隐藏垂直滚动条（滚轮滚动即可）；横向滚动条保留并常显，便于左右滑动查看字段
  :deep(.n-scrollbar-rail--vertical) {
    display: none !important;
  }

  :deep(.n-scrollbar-rail--horizontal) {
    display: block !important;
    opacity: 1 !important;
  }

  :deep(.n-scrollbar-rail--horizontal .n-scrollbar-rail__scrollbar) {
    opacity: 1 !important;
  }

  // ===== 单元格值 =====
  :deep(.cell-plain) {
    color: var(--text);
  }

  :deep(.cell-muted) {
    color: var(--text-muted, #a1a1aa);
  }

  :deep(.cell-old),
  :deep(.cell-new) {
    display: inline-block;
    max-width: 100%;
    padding: 1px 8px;
    border-radius: 4px;
    font-weight: 600;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    line-height: 20px;
    word-break: break-all;
    white-space: normal;
  }

  :deep(.cell-old) {
    color: #b91c1c;
    background: rgba(220, 38, 38, 0.08);
  }

  :deep(.cell-new) {
    color: #15803d;
    background: rgba(22, 163, 74, 0.08);
  }

  :deep(.pk-text) {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    font-weight: 600;
    color: var(--text);
  }

  // ===== 整行按操作着色 =====
  :deep(.row-updated) {
    td { background-color: rgba(245, 158, 11, 0.04); }
  }

  :deep(.row-added) {
    td { background-color: rgba(22, 163, 74, 0.045); }
  }

  :deep(.row-removed) {
    td { background-color: rgba(220, 38, 38, 0.045); }
  }

  // ===== 表头 =====
  :deep(.field-header) {
    font-weight: 600;
    text-align: center;
  }

  :deep(.sub-header) {
    font-size: 12px;
    color: var(--text-secondary);
  }

  // ===== 表格外框（表头+单元格，细浅框线）=====
  :deep(.n-data-table-th),
  :deep(.n-data-table-td) {
    border: 0.5px solid rgba(128, 128, 140, 0.16) !important;
  }

  // ===== 固定列不透明（防穿透）=====
  :deep(.n-data-table-td--fixed-left),
  :deep(.n-data-table-td--fixed-right) {
    background-color: var(--n-merged-td-color, #fff) !important;
  }

  // 着色行的固定列跟随行背景（不透明近似色，避免与行背景割裂）
  :deep(.row-updated .n-data-table-td--fixed-left),
  :deep(.row-updated .n-data-table-td--fixed-right) {
    background-color: #fefbf5 !important;
  }

  :deep(.row-added .n-data-table-td--fixed-left),
  :deep(.row-added .n-data-table-td--fixed-right) {
    background-color: #f5fbf7 !important;
  }

  :deep(.row-removed .n-data-table-td--fixed-left),
  :deep(.row-removed .n-data-table-td--fixed-right) {
    background-color: #fdf5f5 !important;
  }

  :deep(.n-data-table-tr:hover > .n-data-table-td--fixed-left),
  :deep(.n-data-table-tr:hover > .n-data-table-td--fixed-right) {
    background-color: #f4f6ff !important;
  }

  :deep(.n-data-table-th--fixed-left),
  :deep(.n-data-table-th--fixed-right) {
    background-color: var(--n-merged-th-color, #fafafa) !important;
  }

  // ===== 详情抽屉 =====
  :deep(.diff-cell-row) {
    td {
      background-color: rgba(248, 113, 113, 0.08) !important;
    }
  }

  :deep(.detail-name) {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    color: var(--text-secondary);
  }
}
</style>
