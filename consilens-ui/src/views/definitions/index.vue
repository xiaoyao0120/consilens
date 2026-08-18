<script setup>
const tableHeight = "calc(100vh - 250px)";
import { ref, reactive, h, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useMessage, useDialog } from "naive-ui";
import { NButton, NIcon, NDataTable, NTag, NSwitch, NPopconfirm, NInput } from "naive-ui";
import { AddOutline, RefreshOutline, PlayCircleOutline, SparklesOutline } from "@vicons/ionicons5";
import {
  listTaskDefinitions,
  deleteTaskDefinition,
  runTaskDefinition,
  toggleTaskDefinition,
} from "@/api/modules";
import { formatTime } from "@/utils/format";
import StateTag from "@/components/common/StateTag.vue";
import AiAssistantDrawer from "@/components/ai/AiAssistantDrawer.vue";

const router = useRouter();
const message = useMessage();
const dialog = useDialog();

const loading = ref(false);
const aiDrawerShow = ref(false);
const data = reactive({ total: 0, items: [] });
const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
});
const filters = reactive({ keyword: "", enabled: null });

async function loadData() {
  loading.value = true;
  try {
    const result = await listTaskDefinitions({
      page: pagination.page,
      pageSize: pagination.pageSize,
      keyword: filters.keyword || undefined,
      enabled: filters.enabled ?? undefined,
    });
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
  filters.keyword = "";
  filters.enabled = null;
  handleSearch();
}

async function handleRun(row) {
  try {
    const accepted = await runTaskDefinition(row.id);
    message.success(`已提交运行：${accepted.taskId}`);
    router.push(`/instances/${accepted.taskId}`);
  } catch (error) {
    message.error(error?.response?.data?.error || "运行失败");
  }
}

async function handleToggle(row, enabled) {
  try {
    await toggleTaskDefinition(row.id, enabled);
    row.enabled = enabled;
    message.success(enabled ? "已启用" : "已停用");
  } catch (error) {
    message.error(error?.response?.data?.error || "操作失败");
    loadData();
  }
}

function handleDelete(row) {
  dialog.warning({
    title: "删除任务定义",
    content: `确认删除定义「${row.name}」吗？历史运行记录将保留。`,
    positiveText: "删除",
    negativeText: "取消",
    onPositiveClick: async () => {
      try {
        await deleteTaskDefinition(row.id);
        message.success("已删除");
        loadData();
      } catch (error) {
        message.error(error?.response?.data?.error || "删除失败");
      }
    },
  });
}

const columns = [
  {
    title: "名称",
    key: "name",
    minWidth: 180,
    render: (row) =>
      h("a", { class: "app-name-link", onClick: () => router.push(`/definitions/${row.id}`) }, row.name),
  },
  {
    title: "描述",
    key: "description",
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row) => row.description || h("span", { class: "text-muted" }, "-"),
  },
  {
    title: "类型",
    key: "taskType",
    width: 80,
    render: (row) => h("span", { class: "text-secondary" }, row.taskType),
  },
  {
    title: "启用",
    key: "enabled",
    width: 70,
    render: (row) =>
      h(NSwitch, {
        value: row.enabled,
        size: "small",
        onUpdateValue: (v) => handleToggle(row, v),
      }),
  },
  {
    title: "上次运行",
    key: "lastRunAt",
    width: 170,
    render: (row) => h("span", { class: "text-secondary" }, formatTime(row.lastRunAt)),
  },
  {
    title: "创建时间",
    key: "createdAt",
    width: 170,
    render: (row) => h("span", { class: "text-secondary" }, formatTime(row.createdAt)),
  },
  {
    title: "操作",
    key: "actions",
    width: 210,
    render: (row) =>
      h("div", { class: "row-actions" }, [
        h(
          NButton,
          { size: "small", text: true, type: "primary", disabled: !row.enabled, onClick: () => handleRun(row) },
          { default: () => "运行" }
        ),
        h(
          NButton,
          { size: "small", text: true, type: "primary", onClick: () => router.push(`/definitions/${row.id}`) },
          { default: () => "详情" }
        ),
        h(
          NButton,
          { size: "small", text: true, onClick: () => router.push(`/definitions/new?editId=${row.id}`) },
          { default: () => "编辑" }
        ),
        h(
          NButton,
          { size: "small", text: true, type: "error", onClick: () => handleDelete(row) },
          { default: () => "删除" }
        ),
      ]),
  },
];

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">任务定义</div>
        <div class="page-desc">保存可复用的数据校验配置，支持随时运行与停用</div>
      </div>
      <n-button quaternary circle title="AI 辅助" @click="aiDrawerShow = true">
        <template #icon>
          <n-icon><SparklesOutline /></n-icon>
        </template>
      </n-button>
      <n-button type="primary" @click="router.push('/definitions/new')">
        <template #icon>
          <n-icon><AddOutline /></n-icon>
        </template>
        新建定义
      </n-button>
    </div>

    <ai-assistant-drawer v-model:show="aiDrawerShow" />

    <n-card :bordered="true" class="mb-16">
      <div class="filter-bar">
        <n-input
          v-model:value="filters.keyword"
          placeholder="定义名称"
          clearable
          class="filter-item"
          @keyup.enter="handleSearch"
        />
        <n-select
          v-model:value="filters.enabled"
          :options="[
            { label: '全部状态', value: null },
            { label: '已启用', value: true },
            { label: '已停用', value: false },
          ]"
          class="filter-item"
        />
        <n-button type="primary" secondary :loading="loading" @click="handleSearch">查询</n-button>
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
          <span class="card-title">定义列表</span>
          <span class="text-muted">共 {{ data.total }} 条</span>
        </div>
      </template>
      <n-data-table
        :columns="columns"
        :data="data.items"
        :loading="loading"
        :row-key="(row) => row.id"
        :max-height="tableHeight"
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
