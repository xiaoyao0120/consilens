<script setup>
import { ref, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { NButton, NIcon, NDescriptions, NDescriptionsItem } from "naive-ui";
import { ArrowBackOutline } from "@vicons/ionicons5";
import { getArtifactContent } from "@/api/modules";
import StateTag from "@/components/common/StateTag.vue";
import EmptyState from "@/components/common/EmptyState.vue";
import CopyableId from "@/components/common/CopyableId.vue";
import CodeBlock from "@/components/common/CodeBlock.vue";

const route = useRoute();
const router = useRouter();
const artifactId = route.params.artifactId;

const loading = ref(true);
const detail = ref(null); // ArtifactContentDto { artifactId, artifactType, artifactFormat, content }

async function loadDetail() {
  loading.value = true;
  try {
    detail.value = await getArtifactContent(artifactId);
  } finally {
    loading.value = false;
  }
}

onMounted(loadDetail);

function prettyContent() {
  const raw = detail.value?.content;
  if (!raw) return "";
  if (typeof raw === "string") {
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  }
  return JSON.stringify(raw, null, 2);
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="flex-ac detail-head-left">
        <n-button size="small" quaternary circle @click="router.push('/artifacts')">
          <template #icon>
            <n-icon :component="ArrowBackOutline" :size="18" />
          </template>
        </n-button>
        <div>
          <div class="flex-ac detail-title-row">
            <copyable-id class="page-title" :value="artifactId" :length="32" />
            <StateTag v-if="detail" :status="detail.artifactType || detail.type" />
          </div>
          <div class="page-desc">Artifact 详情 · 由任务产生的不可变产物</div>
        </div>
      </div>
    </div>

    <n-spin :show="loading" class="mt-12 detail-spin">
      <n-card v-if="detail" size="small" :bordered="true">
        <n-descriptions label-placement="left" :column="3" bordered size="small" class="mb-16">
          <n-descriptions-item label="类型">{{ detail.artifactType || detail.type }}</n-descriptions-item>
          <n-descriptions-item label="格式">{{ detail.artifactFormat || detail.format || "-" }}</n-descriptions-item>
        </n-descriptions>

        <CodeBlock
          :content="prettyContent()"
          :label="`内容 · ${detail.artifactFormat || 'JSON'}`"
          :max-height="520"
        />
      </n-card>

      <n-card v-else-if="!loading" size="small" :bordered="true">
        <EmptyState
          title="Artifact 不存在"
          action="返回 Artifact 中心"
          @action="router.push('/artifacts')"
        />
      </n-card>
    </n-spin>
  </div>
</template>

<style scoped lang="scss">
.detail-head-left {
  gap: 12px;
}

.detail-title-row {
  gap: 10px;
}

// 让详情页内容自然撑开：覆盖 page-container 的 hidden，允许内容溢出滚动
.page-container {
  overflow: auto;

  > * {
    flex-shrink: 0;
  }
}
</style>
