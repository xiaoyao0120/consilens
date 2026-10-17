import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";

/**
 * 列表页筛选 / 分页 ↔ URL query 双向同步（可分享 / 刷新保留）：
 * - 初始化：按 fields 声明从 query 恢复筛选值（query 中缺省时保持 filters 的初始默认值），
 *   并从 query 恢复页码（缺省为第 1 页）
 * - syncToUrl()：加载前写回（空值不写入；页码 > 1 才写入）
 * - filtersActive：任一筛选非空
 *
 * fields: { 筛选字段: { queryKey?, parse?, serialize? } }
 * - queryKey：URL 参数名（缺省与字段名一致）
 * - parse：query 值 → 筛选值（缺省原样取用，如 ?enabled=true 这类三态转换在此实现）
 * - serialize：筛选值 → query 字符串（缺省 String，如毫秒时间戳用 String(filters.x)）
 */
export function useListQuerySync(filters, pagination, fields) {
  const route = useRoute();
  const router = useRouter();

  for (const [key, spec = {}] of Object.entries(fields)) {
    const raw = route.query[spec.queryKey || key];
    if (raw === undefined) continue;
    filters[key] = spec.parse ? spec.parse(raw) : raw;
  }
  pagination.page = Number(route.query.page) || 1;

  const isEmpty = (value) => value === null || value === undefined || value === "";

  const filtersActive = computed(() => Object.values(filters).some((value) => !isEmpty(value)));

  function syncToUrl() {
    const query = {};
    for (const [key, spec = {}] of Object.entries(fields)) {
      const value = filters[key];
      if (isEmpty(value)) continue;
      query[spec.queryKey || key] = spec.serialize ? spec.serialize(value) : String(value);
    }
    if (pagination.page > 1) query.page = String(pagination.page);
    // 值与 query 一致时跳过 replace：避免 onMounted 的首次加载也产生一次路由替换
    // （会触发路由 watch 连锁，并在浏览器历史里留下无意义记录）
    const current = route.query;
    const sameKeys = Object.keys(query).length === Object.keys(current).length;
    if (sameKeys && Object.keys(query).every((key) => String(current[key]) === query[key])) {
      return;
    }
    router.replace({ query });
  }

  return { filtersActive, syncToUrl };
}
