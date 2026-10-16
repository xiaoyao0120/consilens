<script setup>
import { ref, reactive, h, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useMessage, useDialog } from "naive-ui";
import { NTag } from "naive-ui";
import { AddOutline, RefreshOutline, SparklesOutline } from "@vicons/ionicons5";
import {
  listTaskDefinitions,
  deleteTaskDefinition,
  runTaskDefinition,
  toggleTaskDefinition,
} from "@/api/modules";
import { useListQuerySync } from "@/composables/useListQuerySync";
import AiAssistantDrawer from "@/components/ai/AiAssistantDrawer.vue";
import EmptyState from "@/components/common/EmptyState.vue";
import SkeletonTable from "@/components/common/SkeletonTable.vue";
import TimeText from "@/components/common/TimeText.vue";

const router = useRouter();
const message = useMessage();
const dialog = useDialog();

const loading = ref(false);
const loadError = ref(false);
const aiDrawerShow = ref(false);
const data = reactive({ total: 0, items: [] });
const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
  prefix: ({ itemCount }) => `共 ${itemCount} 条`,
});
// 筛选与 URL 双向同步：初始从 query 恢复，每次加载写回
const filters = reactive({
  keyword: "",
  enabled: null,
});

const { filtersActive, syncToUrl } = useListQuerySync(filters, pagination, {
  keyword: {},
  // 三态：true / false / null（未筛选）
  enabled: { parse: (v) => (v === "true" ? true : v === "false" ? false : null) },
});

async function loadData() {
  loading.value = true;
  loadError.value = false;
  syncToUrl();
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
  } catch {
    loadError.value = true;
    data.items = [];
    pagination.itemCount = 0;
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
    width: 96,
    render: (row) => h(NTag, { size: "small", bordered: false }, row.taskType),
  },
  {
    title: "状态",
    key: "enabled",
    width: 84,
    render: (row) =>
      h(
        NTag,
        { size: "small", bordered: false, type: row.enabled ? "success" : "default" },
        { default: () => (row.enabled ? "已启用" : "已停用") }
      ),
  },
  {
    title: "上次运行",
    key: "lastRunAt",
    width: 170,
    render: (row) => h(TimeText, { value: row.lastRunAt }),
  },
  {
    title: "创建时间",
    key: "createdAt",
    width: 170,
    render: (row) => h(TimeText, { value: row.createdAt }),
  },
  {
    title: "操作",
    key: "actions",
    width: 210,
    render: (row) =>
      h("div", { class: "row-actions" }, [
        row.enabled
          ? h("a", { class: "row-action", onClick: () => handleRun(row) }, "运行")
          : h("span", { class: "row-action-disabled" }, "运行"),
        h(
          "a",
          { class: "row-action", onClick: () => handleToggle(row, !row.enabled) },
          row.enabled ? "停用" : "启用"
        ),
        h("a", { class: "row-action", onClick: () => router.push(`/definitions/${row.id}`) }, "详情"),
        h("a", { class: "row-action", onClick: () => router.push(`/definitions/new?editId=${row.id}`) }, "编辑"),
        h("a", { class: "row-action-danger", onClick: () => handleDelete(row) }, "删除"),
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
      <div class="head-actions">
        <n-button size="small" quaternary circle title="AI 辅助" @click="aiDrawerShow = true">
          <template #icon>
            <n-icon><SparklesOutline /></n-icon>
          </template>
        </n-button>
        <n-button type="primary" size="small" @click="router.push('/definitions/new')">
          <template #icon>
            <n-icon><AddOutline /></n-icon>
          </template>
          新建定义
        </n-button>
      </div>
    </div>

    <ai-assistant-drawer v-model:show="aiDrawerShow" />

    <n-card size="small" class="mt-12 mb-12">
      <div class="filter-bar">
        <n-input
          v-model:value="filters.keyword"
          placeholder="定义名称"
          clearable
          size="small"
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
          size="small"
          class="filter-item"
        />
        <n-button size="small" type="primary" :loading="loading" @click="handleSearch">查询</n-button>
        <n-button size="small" @click="handleReset">重置</n-button>
        <n-button size="small" quaternary circle title="刷新" :loading="loading" @click="loadData">
          <template #icon>
            <n-icon><RefreshOutline /></n-icon>
          </template>
        </n-button>
      </div>
    </n-card>

    <n-card size="small" class="table-card">
      <SkeletonTable v-if="loading && !data.items.length" :rows="8" />
      <EmptyState
        v-else-if="!loading && !data.items.length"
        class="flex-1"
        :title="loadError ? '加载失败' : filtersActive ? '没有匹配的定义' : '还没有任务定义'"
        :description="loadError ? '网络或服务异常，请重试' : filtersActive ? '尝试调整或清除筛选条件' : '保存可复用的数据校验配置后即可一键运行'"
        :action="loadError ? '重试' : filtersActive ? '清除筛选' : '新建定义'"
        @action="loadError ? loadData() : filtersActive ? handleReset() : router.push('/definitions/new')"
      />
      <n-data-table
        v-else
        class="main-table"
        :columns="columns"
        :data="data.items"
        :loading="loading"
        :row-key="(row) => row.id"
        flex-height
        :bordered="false"
        size="small"
        :pagination="pagination"
        remote
        @update:page="(page) => { pagination.page = page; loadData(); }"
        @update:page-size="(size) => { pagination.pageSize = size; pagination.page = 1; loadData(); }"
      />
    </n-card>
  </div>
</template>
