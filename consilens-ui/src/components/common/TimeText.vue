<script setup>
import { computed } from "vue";

const props = defineProps({
  value: { type: [String, Number], default: "" },
});

function parseTs(value) {
  if (!value) return null;
  const s = String(value);
  // 纯数字时间戳（毫秒/秒）按数值解析：new Date("1791953200000") 会得到 Invalid Date
  if (/^\d{10,19}$/.test(s)) return Number(s);
  // 容忍毫秒溢出（后端可能输出 >19 位的时间戳字符串）
  const t = new Date(s.length >= 23 ? s.slice(0, 23) : s).getTime();
  return Number.isNaN(t) ? null : t;
}

const absolute = computed(() => {
  const t = parseTs(props.value);
  if (!t) return "—";
  const d = new Date(t);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
});

const relative = computed(() => {
  const t = parseTs(props.value);
  if (!t) return "—";
  const diff = Date.now() - t;
  if (diff < 60000) return "刚刚";
  if (diff < 3600000) return Math.floor(diff / 60000) + " 分钟前";
  if (diff < 86400000) return Math.floor(diff / 3600000) + " 小时前";
  if (diff < 86400000 * 30) return Math.floor(diff / 86400000) + " 天前";
  return absolute.value.slice(0, 10);
});
</script>

<template>
  <span class="time-text num" :title="absolute">{{ relative }}</span>
</template>

<style scoped lang="scss">
.time-text {
  white-space: nowrap;
}
</style>
