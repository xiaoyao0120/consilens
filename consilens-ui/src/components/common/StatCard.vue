<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: "-" },
  color: { type: String, default: "var(--text)" },
  extra: { type: String, default: "" },
  clickable: { type: Boolean, default: false },
});

defineEmits(["click"]);
</script>

<template>
  <n-card
    class="stat-card"
    size="small"
    :class="{ clickable }"
    @click="$emit('click')"
  >
    <div class="stat-label text-secondary">{{ label }}</div>
    <div class="stat-value" :style="{ color }">{{ value }}</div>
    <div v-if="extra" class="stat-extra text-muted">{{ extra }}</div>
  </n-card>
</template>

<style scoped lang="scss">
.stat-card {
  &.clickable {
    cursor: pointer;
    transition: border-color 0.15s ease;

    &:hover {
      border-color: var(--primary);
    }
  }

  :deep(.n-card__content) {
    padding: 16px 20px;
  }
}

.stat-label {
  font-size: 12px;
}

.stat-value {
  margin-top: 6px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-extra {
  margin-top: 6px;
  font-size: 12px;
}
</style>
