<script setup>
import { h } from "vue";
import { NIcon } from "naive-ui";
import { CloudOfflineOutline } from "@vicons/ionicons5";

defineProps({
  title: { type: String, default: "暂无数据" },
  description: { type: String, default: "" },
  action: { type: String, default: "" },
});

defineEmits(["action"]);
</script>

<template>
  <div class="empty-state">
    <n-icon :size="48" color="var(--text-muted)">
      <CloudOfflineOutline />
    </n-icon>
    <div class="empty-title">{{ title }}</div>
    <div v-if="description" class="empty-desc text-secondary">{{ description }}</div>
    <n-button v-if="action" type="primary" class="mt-16" @click="$emit('action')">
      {{ action }}
    </n-button>
  </div>
</template>

<style scoped lang="scss">
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 0;
}

.empty-title {
  margin-top: 12px;
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
}

.empty-desc {
  margin-top: 4px;
  font-size: 13px;
  max-width: 420px;
  text-align: center;
}
</style>
