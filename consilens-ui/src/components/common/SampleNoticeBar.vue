<script setup>
import { computed } from "vue";
import { formatNumber } from "@/utils/format";

const props = defineProps({
  // diff-report 字段
  total: { type: Number, default: null },
  sampleSize: { type: Number, default: null },
  truncated: { type: Boolean, default: false },
  hasDifferences: { type: Boolean, default: false },
});

const variant = computed(() => {
  if (!props.hasDifferences) return "success";
  return props.truncated ? "warning" : "info";
});

const message = computed(() => {
  if (!props.hasDifferences) {
    return "本次比对未发现差异，两表数据完全一致。";
  }
  if (!props.sampleSize || props.sampleSize >= props.total) {
    return `共发现 ${formatNumber(props.total)} 条差异记录。`;
  }
  return `共发现 ${formatNumber(props.total)} 条差异记录，当前展示前 ${formatNumber(props.sampleSize)} 条样本`;
});

const tip = computed(() => {
  if (!props.hasDifferences || !props.truncated) return "";
  return "样本由运行节点抽样采集，完整差异明细请查看 Artifact 内容。";
});
</script>

<template>
  <n-alert
    v-if="total !== null"
    :type="variant"
    :show-icon="true"
    :title="message"
    style="margin-bottom: 14px"
  >
    <template v-if="tip"> {{ tip }} </template>
  </n-alert>
</template>
