<script setup>
import { computed, ref, watch } from "vue";
import { getArtifactContent } from "@/api/modules";

const props = defineProps({
  artifact: { type: Object, default: null },
});

const loading = ref(false);
const raw = ref("");
const error = ref("");

const format = computed(() => props.artifact?.format || "json");

async function loadContent() {
  if (!props.artifact?.artifactId) return;
  loading.value = true;
  error.value = "";
  try {
    raw.value = await getArtifactContent(props.artifact.artifactId, { silent: true });
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.artifact?.artifactId,
  () => loadContent(),
  { immediate: true }
);

const prettyJson = computed(() => {
  if (typeof raw.value === "string") {
    try {
      return JSON.stringify(JSON.parse(raw.value), null, 2);
    } catch {
      return raw.value;
    }
  }
  return JSON.stringify(raw.value, null, 2);
});
</script>

<template>
  <div class="artifact-viewer">
    <div v-if="loading" class="viewer-loading">
      <n-spin size="small" /> 加载中…
    </div>
    <n-alert v-else-if="error" type="error" :title="'内容读取失败'" size="small">
      {{ error }}
    </n-alert>
    <n-code v-else-if="format === 'json'" :code="prettyJson" language="json" word-wrap class="viewer-code" />
    <n-code v-else-if="format === 'yaml'" :code="String(raw)" language="yaml" word-wrap class="viewer-code" />
    <n-code v-else :code="String(raw)" word-wrap class="viewer-code" />
  </div>
</template>

<style scoped lang="scss">
.viewer-loading {
  padding: 24px;
  text-align: center;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.viewer-code {
  font-size: 12px;
  background: transparent;
}
</style>
