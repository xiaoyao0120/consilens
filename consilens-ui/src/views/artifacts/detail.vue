<script setup>
import { ref, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { NButton, NIcon, NDescriptions, NDescriptionsItem } from "naive-ui";
import { ArrowBackOutline } from "@vicons/ionicons5";
import { getArtifact } from "@/api/modules";
import StateTag from "@/components/common/StateTag.vue";

const route = useRoute();
const router = useRouter();
const artifactId = route.params.artifactId;

const loading = ref(true);
const detail = ref(null); // ArtifactContentDto { artifactId, artifactType, artifactFormat, content }

async function loadDetail() {
  loading.value = true;
  try {
    detail.value = await getArtifact(artifactId);
  } finally {
    loading.value = false;
  }
}

onMounted(loadDetail);

function copyId() {
  navigator.clipboard.writeText(artifactId);
}

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
      <div class="flex-ac" style="gap: 12px">
        <n-button quaternary circle @click="router.push('/artifacts')">
          <template #icon>
            <n-icon :component="ArrowBackOutline" :size="18" />
          </template>
        </n-button>
        <div>
          <div class="flex-ac" style="gap: 10px">
            <span class="page-title mono">{{ artifactId }}</span>
            <StateTag v-if="detail" :status="detail.artifactType" />
          </div>
          <div class="page-desc">Artifact 详情 · 由任务产生的不可变产物</div>
        </div>
      </div>
      <n-button size="small" @click="copyId">复制 ID</n-button>
    </div>

    <n-card v-if="detail" :bordered="true">
      <n-descriptions label-placement="left" :column="3" bordered size="small" class="mb-16">
        <n-descriptions-item label="类型">{{ detail.artifactType }}</n-descriptions-item>
        <n-descriptions-item label="格式">{{ detail.artifactFormat || "-" }}</n-descriptions-item>
      </n-descriptions>

      <div class="card-title mb-16">内容</div>
      <n-code :code="prettyContent()" language="json" word-wrap />
    </n-card>

    <n-card v-else-if="!loading" :bordered="true">
      <n-empty description="Artifact 不存在">
        <template #extra>
          <n-button size="small" @click="router.push('/artifacts')">返回 Artifact 中心</n-button>
        </template>
      </n-empty>
    </n-card>
  </div>
</template>
