<script setup>
import { ref, onMounted, h } from "vue";
import { useMessage } from "naive-ui";
import { NButton, NIcon, NStatistic, NDrawer, NDrawerContent, NDescriptions, NDescriptionsItem, NSpin } from "naive-ui";
import { RefreshOutline } from "@vicons/ionicons5";
import { listNodes, getNode } from "@/api/modules";
import StateTag from "@/components/common/StateTag.vue";

const message = useMessage();

const loading = ref(false);
const nodes = ref([]);
const refreshing = ref(false);

async function loadNodes(showRefresh = false) {
  if (showRefresh) refreshing.value = true;
  else loading.value = true;
  try {
    nodes.value = await listNodes();
  } finally {
    loading.value = false;
    refreshing.value = false;
  }
}

// ===== 详情 drawer =====
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref(null);

async function openDetail(node) {
  detail.value = node;
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    detail.value = await getNode(node.nodeKey);
  } catch (e) {
    message.error(e.message);
  } finally {
    detailLoading.value = false;
  }
}

function memoryBar(availableMb) {
  if (availableMb === null || availableMb === undefined) return 0;
  // 以 16GB 为基准估算内存占比
  return Math.min(100, Math.round((availableMb / 16384) * 100));
}

function loadBar(loadAverage) {
  if (loadAverage === null || loadAverage === undefined) return 0;
  return Math.min(100, Math.round(loadAverage * 10));
}

onMounted(() => loadNodes());
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">节点与集群</div>
        <div class="page-desc">已注册的执行节点与集群状态</div>
      </div>
      <n-button :loading="refreshing" @click="loadNodes(true)">
        <template #icon>
          <n-icon><RefreshOutline /></n-icon>
        </template>
        刷新拓扑
      </n-button>
    </div>

    <n-grid :cols="3" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-grid-item v-for="node in nodes" :key="node.nodeKey" span="3 s:2 m:1">
        <n-card :bordered="true" class="node-card" @click="openDetail(node)">
          <div class="flex-bc mb-16">
            <n-tag type="info" size="small" class="mono">{{ node.nodeKey }}</n-tag>
            <StateTag :status="node.status" />
          </div>
          <div class="node-meta mb-16">
            <span class="mono">{{ node.host }}:{{ node.port }}</span>
          </div>
          <n-statistic label="负载均值 (load average)" :value="node.loadAverage?.toFixed?.(2) ?? node.loadAverage ?? '-'" />
          <div class="mt-8">
            <n-progress
              type="line"
              :percentage="memoryBar(node.availableMemoryMb)"
              :show-indicator="false"
              color="var(--success)"
              rail-color="var(--border)"
              height="6px"
            />
            <div class="text-muted" style="font-size: 12px; margin-top: 4px">
              可用内存 {{ node.availableMemoryMb ? Math.round(node.availableMemoryMb) + " MB" : "-" }}
            </div>
          </div>
        </n-card>
      </n-grid-item>
    </n-grid>

    <n-empty v-if="!loading && !nodes.length" description="暂无注册节点" />

    <n-drawer v-model:show="detailVisible" :width="480">
      <n-drawer-content :title="detail?.nodeKey || '节点详情'" closable>
        <n-spin :show="detailLoading">
          <n-descriptions v-if="detail" label-placement="left" :column="1" bordered size="small">
            <n-descriptions-item label="节点 Key">{{ detail.nodeKey }}</n-descriptions-item>
            <n-descriptions-item label="地址">{{ detail.host }}:{{ detail.port }}</n-descriptions-item>
            <n-descriptions-item label="状态">
              <StateTag :status="detail.status" />
            </n-descriptions-item>
            <n-descriptions-item label="负载均值">{{ detail.loadAverage?.toFixed?.(2) ?? detail.loadAverage ?? "-" }}</n-descriptions-item>
            <n-descriptions-item label="可用内存">
              {{ detail.availableMemoryMb ? Math.round(detail.availableMemoryMb) + " MB" : "-" }}
            </n-descriptions-item>
          </n-descriptions>
        </n-spin>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped lang="scss">
.node-card {
  cursor: pointer;
  transition: border-color 0.2s;

  &:hover {
    border-color: var(--border-strong);
  }
}

.node-meta {
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
