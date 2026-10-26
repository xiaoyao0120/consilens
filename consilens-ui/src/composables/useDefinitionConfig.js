import { computed } from "vue";

// ===== 运行配置组装与确认页摘要 =====
export function useDefinitionConfig({
  planForm,
  compareForm,
  advForm,
  runForm,
  platformForm,
  platformProperties,
}) {
  // 组装 hints：主键映射 / 比较字段（同名简化或映射）/ 排除字段
  function buildHints() {
    const hints = {};
    const keyMappings = compareForm.keyMappings.filter((m) => m.source && m.target);
    if (keyMappings.some((m) => m.source !== m.target)) {
      hints.keyMappings = keyMappings;
    }
    const fieldMappings = compareForm.fieldMappings.filter((m) => m.source && m.target);
    if (fieldMappings.length) {
      if (fieldMappings.every((m) => m.source === m.target)) {
        hints.compareColumns = fieldMappings.map((m) => m.source);
      } else {
        hints.compareColumns = fieldMappings;
      }
    }
    if (compareForm.ignoreColumns.length) {
      hints.ignoreColumns = compareForm.ignoreColumns;
    }
    // 跨方言归一化：仅提交用户显式填写的项，未填写时保持引擎默认（不生成空规则）
    const normalization = {};
    if (advForm.decimalPrecision !== null && advForm.decimalPrecision !== undefined
        && advForm.decimalPrecision !== "") {
      normalization.global = {
        ...(normalization.global || {}),
        decimal: { precision: Number(advForm.decimalPrecision) },
      };
    }
    if (advForm.timezone) {
      normalization.global = {
        ...(normalization.global || {}),
        timestamp: { timezone: advForm.timezone },
        datetime: { timezone: advForm.timezone },
      };
    }
    if (Object.keys(normalization).length) {
      hints.normalization = normalization;
    }
    return hints;
  }

  // 只序列化当前模式对应的资源：服务端 resource() 优先使用 query，
  // 同时写入 table 和 query 会让旧 SQL 悄悄覆盖用户刚选择的表。
  function buildResource(side) {
    const form = planForm[side];
    const resource = {
      type: form.type,
      datasourceId: form.datasourceId || undefined,
      database: form.database || undefined,
    };
    if (form.mode === "sql") {
      resource.query = form.query.trim() || undefined;
    } else {
      resource.table = form.table.trim() || undefined;
    }
    return resource;
  }

  function buildConfig() {
    const config = {
      version: "1.0",
      goal: planForm.goal,
      source: buildResource("source"),
      target: buildResource("target"),
      keys: compareForm.keyMappings.map((m) => m.source).filter(Boolean),
      comparison: {},
      executionOptions: {
        timeoutMs: runForm.timeoutMs || undefined,
        bisectionFactor: advForm.bisectionFactor,
        bisectionThreshold: advForm.bisectionThreshold,
        checksumAlgorithm: advForm.algorithm,
        localCompareMode: advForm.localCompareMode,
        validateUniqueKeys: advForm.validateUniqueKeys,
        allowNullableKeys: advForm.allowNullableKeys,
        maxDifferences: advForm.maxDifferences || undefined,
        batchSize: advForm.batchSize,
      },
      hints: {
        ...buildHints(),
        strategyPreference: {
          preferredPlans: [advForm.mode.toUpperCase()],
          allowFallback: true,
        },
      },
    };
    if (platformForm.platform !== "local") {
      config.platform = platformForm.platform;
      config.properties = platformProperties();
    }
    const hints = buildHints();
    if (hints.normalization) config.normalization = hints.normalization;
    if (hints.keyMappings) config.comparison.keyMappings = hints.keyMappings;
    if (hints.ignoreColumns) config.comparison.ignoreColumns = hints.ignoreColumns;
    if (Array.isArray(hints.compareColumns)) {
      if (hints.compareColumns.length && typeof hints.compareColumns[0] === "object") {
        config.comparison.fieldMappings = hints.compareColumns;
      } else {
        config.comparison.fields = hints.compareColumns;
      }
    }
    return config;
  }

  const configDisplay = computed(() => {
    try {
      return JSON.stringify(buildConfig(), null, 2);
    } catch (error) {
      return `配置错误：${error.message}`;
    }
  });

  function resourceLabel(side) {
    const form = planForm[side];
    if (form.mode === "sql") {
      return form.query.trim() ? `SQL · ${form.query.trim()}` : "SQL · (未填写查询)";
    }
    return `${form.type} · ${form.table || "(未选择表)"}`;
  }

  const summary = computed(() => [
    { label: "目标描述", value: planForm.goal },
    { label: "源端", value: resourceLabel("source") },
    { label: "目标端", value: resourceLabel("target") },
    { label: "主键列", value: compareForm.keyMappings.filter((m) => m.source).map((m) => m.source).join(", ") || "-" },
    { label: "超时时间", value: `${runForm.timeoutMs} ms` },
    { label: "试运行", value: runForm.dryRun ? "开启" : "关闭" },
  ]);

  return { buildConfig, configDisplay, summary };
}
