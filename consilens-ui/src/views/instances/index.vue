<script setup>
const tableHeight = "calc(100vh - 250px)";
import { ref, reactive, onMounted, h } from "vue";
import { useRouter } from "vue-router";
import { useMessage, useDialog } from "naive-ui";
import { NButton, NIcon, NPopconfirm } from "naive-ui";
import { RefreshOutline } from "@vicons/ionicons5";
import { listTaskInstances, retryTask, cancelTask } from "@/api/modules";
import { TASK_STATUS_OPTIONS } from "@/common/statusMapping";
import { formatTime, formatDuration, buildPageParams } from "@/utils/format";
import StateTag from "@/components/common/StateTag.vue";
import DiffSummaryBadge from "@/components/common/DiffSummaryBadge.vue";

const router = useRouter();
const message = useMessage();
const dialog = useDialog();

const loading = ref(false);
const data = reactive({ total: 0, items: [] });
const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
});

const filters = reactive({
  status: null,
  keyword: "",
  executeNodeKey: "",
  startTime: null,
  endTime: null,
});

async function loadData() {
  loading.value = true;
  try {
    const params = buildPageParams(pagination.page, pagination.pageSize, {
      status: filters.status ? [filters.status] : undefined,
      keyword: filters.keyword,
      executeNodeKey: filters.executeNodeKey,
      startTime: filters.startTime,
      endTime: filters.endTime,
      includeDiffSummary: true,
    });
    const result = await listTaskInstances(params);
    data.total = result.total;
    data.items = result.items || [];
    pagination.itemCount = result.total;
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.page = 1;
  loadData();
}

function handleReset() {
  filters.status = null;
  filters.keyword = "";
  filters.executeNodeKey = "";
  filters.startTime = null;
  filters.endTime = null;
  handleSearch();
}

const columns = [
  {
    title: "实例 ID",
    key: "id",
    width: 220,
    fixed: "left",
    ellipsis: { tooltip: true },
    render: (row) =>
      h(
        "a",
        {
          class: "app-name-link mono",
          style: "white-space: nowrap; display: inline-block;",
          onClick: () => router.push(`/instances/${row.id || row.taskId}`),
        },
        row.id || row.taskId
      ),
  },
  { title: "序号", key: "serialNo", width: 160, ellipsis: { tooltip: true }, render: (row) => h("span", { class: "mono text-secondary" }, row.serialNo) },
  {
    title: "定义",
    key: "definition",
    width: 130,
    ellipsis: { tooltip: true },
    render: (row) =>
      row.definitionName
        ? h("a", { class: "app-name-link", onClick: () => router.push(`/definitions/${row.definitionId}`) }, row.definitionName)
        : row.definitionId
          ? h("span", { class: "text-muted" }, "已删除的定义")
          : h("span", { class: "text-muted" }, "外部直传"),
  },
  { title: "类型", key: "taskType", width: 80, render: (row) => h("span", { class: "text-secondary" }, row.taskType) },
  {
    title: "状态",
    key: "status",
    width: 100,
    render: (row) => h(StateTag, { status: row.status }),
  },
  {
    title: "差异摘要",
    key: "diffSummary",
    width: 160,
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
  { title: "执行节点", key: "executeNodeKey", width: 130, ellipsis: { tooltip: true }, render: (row) => row.executeNodeKey || "-" },
  { title: "重试", key: "retryCount", width: 60, render: (row) => row.retryCount ?? 0 },
  { title: "提交时间", key: "submitTime", width: 150, render: (row) => h("span", { class: "text-secondary" }, formatTime(row.submitTime)) },
  {
    title: "耗时",
    key: "duration",
    width: 100,
    render: (row) => h("span", { class: "text-secondary" }, formatDuration(row.startTime, row.endTime)),
  },
  {
    title: "操作",
    key: "actions",
    width: 130,
    fixed: "right",
    render: (row) => renderActions(row),
  },
];

function renderActions(row) {
  const actions = [
    h(
      "a",
      { class: "row-action", onClick: () => router.push(`/instances/${row.id || row.taskId}`) },
      "详情"
    ),
  ];
  if ((row.availableNextActions || []).includes("retry")) {
    actions.push(
      h(
        NPopconfirm,
        { onPositiveClick: () => handleRetry(row), positiveText: "确认重试", negativeText: "取消" },
        {
          trigger: () => h("a", { class: "row-action" }, "重试"),
          default: () => `确认重试任务 ${row.taskId} 吗？`,
        }
      )
    );
  }
  if ((row.availableNextActions || []).includes("cancel")) {
    actions.push(
      h(
        NPopconfirm,
        { onPositiveClick: () => handleCancel(row), positiveText: "确认取消", negativeText: "取消" },
        {
          trigger: () => h("a", { class: "row-action-danger" }, "取消"),
          default: () => `确认取消任务 ${row.taskId} 吗？`,
        }
      )
    );
  }
  return h("div", { class: "row-actions" }, { default: () => actions });
}

async function handleRetry(row) {
  await retryTask(row.taskId);
  message.success("已发起重试");
  loadData();
}

async function handleCancel(row) {
  await cancelTask(row.taskId);
  message.success("已发起取消");
  loadData();
}

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">运行实例</div>
        <div class="page-desc">任务定义的每一次执行记录，支持外部直传运行</div>
      </div>
    </div>

    <n-card :bordered="true" class="mb-16">
      <div class="filter-bar">
        <n-select
          v-model:value="filters.status"
          :options="TASK_STATUS_OPTIONS"
          placeholder="全部状态"
          clearable
          class="filter-item"
        />
        <n-input
          v-model:value="filters.keyword"
          placeholder="任务 ID / 序号"
          clearable
          class="filter-item"
          @keyup.enter="handleSearch"
        />
        <n-input
          v-model:value="filters.executeNodeKey"
          placeholder="执行节点"
          clearable
          class="filter-item"
          @keyup.enter="handleSearch"
        />
        <n-date-picker
          v-model:value="filters.startTime"
          type="datetime"
          clearable
          class="filter-item"
          placeholder="开始时间"
        />
        <n-date-picker
          v-model:value="filters.endTime"
          type="datetime"
          clearable
          class="filter-item"
          placeholder="结束时间"
        />
        <n-button type="primary" secondary :loading="loading" @click="handleSearch">
          查询
        </n-button>
        <n-button @click="handleReset">重置</n-button>
        <n-button quaternary circle class="ml-8" title="刷新" :loading="loading" @click="loadData">
          <template #icon>
            <n-icon><RefreshOutline /></n-icon>
          </template>
        </n-button>
      </div>
    </n-card>

    <n-card :bordered="true">
      <template #header>
        <div class="flex-bc">
          <span class="card-title">任务列表</span>
          <span class="text-muted">共 {{ data.total }} 条</span>
        </div>
      </template>
      <n-data-table
        :columns="columns"
        :data="data.items"
        :loading="loading"
        :row-key="(row) => row.taskId"
        :max-height="tableHeight"
        :scroll-x="1540"
        :bordered="false"
        size="small"
        :pagination="pagination"
        @update:page="(page) => { pagination.page = page; loadData(); }"
        @update:page-size="(size) => { pagination.pageSize = size; pagination.page = 1; loadData(); }"
      />
    </n-card>
  </div>
</template>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.filter-item {
  width: 180px;
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

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.row-action {
  font-size: 13px;
  color: var(--primary);
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.row-action-danger {
  font-size: 13px;
  color: var(--danger);
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.diff-link {
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}
// 列表横向滚动条始终可见，避免字段被隐藏的错觉
:deep(.n-data-table .n-scrollbar-rail--horizontal) {
  display: block !important;
  opacity: 1 !important;
}

:deep(.n-data-table .n-scrollbar-rail--horizontal .n-scrollbar-rail__scrollbar) {
  opacity: 1 !important;
}

// ===== 固定列不透明(防穿透),与差异明细列表处理一致 =====
:deep(.n-data-table-td--fixed-left),
:deep(.n-data-table-td--fixed-right) {
  background-color: var(--n-merged-td-color, #fff) !important;
}

:deep(.n-data-table-tr:hover > .n-data-table-td--fixed-left),
:deep(.n-data-table-tr:hover > .n-data-table-td--fixed-right) {
  background-color: #f4f6ff !important;
}

:deep(.n-data-table-th--fixed-left),
:deep(.n-data-table-th--fixed-right) {
  background-color: var(--n-merged-th-color, #fafafa) !important;
}
</style>
