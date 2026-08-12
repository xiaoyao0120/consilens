<script setup>
const tableHeight = "calc(100vh - 250px)";
import { ref, reactive, onMounted, h } from "vue";
import { useRouter } from "vue-router";
import { useMessage } from "naive-ui";
import { NButton, NIcon, NSpace, NDataTable } from "naive-ui";
import { RefreshOutline, EyeOutline, CopyOutline } from "@vicons/ionicons5";
import { listArtifacts } from "@/api/modules";
import { ARTIFACT_TYPE_OPTIONS } from "@/common/statusMapping";
import { formatTime, formatBytes, buildPageParams } from "@/utils/format";
import StateTag from "@/components/common/StateTag.vue";

const router = useRouter();
const message = useMessage();

const loading = ref(false);
const data = reactive({ total: 0, items: [] });
const pagination = reactive({
  page: 1,
  pageSize: 20,
  itemCount: 0,
  pageSizes: [10, 20, 50, 100],
  showSizePicker: true,
});

const filters = reactive({
  artifactType: null,
  keyword: "",
  startTime: null,
  endTime: null,
});

const columns = [
  {
    title: "Artifact ID",
    key: "id",
    render: (row) =>
      h(
        "a",
        {
          class: "app-name-link",
          onClick: () => router.push(`/artifacts/${row.id}`),
        },
        row.id
      ),
  },
  {
    title: "类型",
    key: "artifactType",
    width: 110,
    render: (row) => h(StateTag, { status: row.artifactType }),
  },
  { title: "格式", key: "format", width: 80, render: (row) => row.format || "-" },
  { title: "大小", key: "sizeBytes", width: 100, render: (row) => formatBytes(row.sizeBytes) },
  {
    title: "元数据",
    key: "metadata",
    render: (row) =>
      row.metadata && Object.keys(row.metadata).length
        ? Object.entries(row.metadata)
            .map(([k, v]) => `${k}: ${typeof v === "object" ? JSON.stringify(v) : v}`)
            .join(" · ")
        : "-",
  },
  { title: "创建时间", key: "createdAt", width: 170, render: (row) => formatTime(row.createdAt) },
  {
    title: "操作",
    key: "actions",
    width: 160,
    render: (row) =>
      h(NSpace, { size: 4 }, { default: () => [
        h(NButton, { size: "small", quaternary: true, type: "primary", onClick: () => router.push(`/artifacts/${row.id}`) },
          { icon: () => h(NIcon, null, { default: () => h(EyeOutline) }), default: () => "查看" }),
        h(NButton, { size: "small", quaternary: true, onClick: () => copyId(row.id) },
          { icon: () => h(NIcon, null, { default: () => h(CopyOutline) }), default: () => "复制 ID" }),
      ] }),
  },
];

function copyId(id) {
  navigator.clipboard.writeText(id);
  message.success("已复制 artifactId");
}

async function loadData() {
  loading.value = true;
  try {
    const result = await listArtifacts(
      buildPageParams(pagination.page, pagination.pageSize, {
        artifactType: filters.artifactType ? [filters.artifactType] : undefined,
        keyword: filters.keyword,
        startTime: filters.startTime,
        endTime: filters.endTime,
      })
    );
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
  filters.artifactType = null;
  filters.keyword = "";
  filters.startTime = null;
  filters.endTime = null;
  handleSearch();
}

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">Artifact 中心</div>
        <div class="page-desc">所有任务产生的配置、校验结果、诊断报告与运行结果</div>
      </div>
    </div>

    <n-card :bordered="true" class="mb-16">
      <div class="filter-bar">
        <n-select
          v-model:value="filters.artifactType"
          :options="ARTIFACT_TYPE_OPTIONS"
          placeholder="全部类型"
          clearable
          class="filter-item"
        />
        <n-input
          v-model:value="filters.keyword"
          placeholder="搜索 artifactId"
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
          <span class="card-title">Artifact 列表</span>
          <span class="text-muted">共 {{ data.total }} 条</span>
        </div>
      </template>
      <n-data-table
        :columns="columns"
        :data="data.items"
        :loading="loading"
        :row-key="(row) => row.id"
        :max-height="tableHeight"
        size="small"
        :bordered="false"
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
</style>
