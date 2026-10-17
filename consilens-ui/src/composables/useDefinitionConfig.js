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
    return hints;
  }

  function buildConfig() {
    const config = {
      version: "1.0",
      goal: planForm.goal,
      source: {
        type: planForm.source.type,
        table: planForm.source.table.trim() || undefined,
        query: planForm.source.query.trim() || undefined,
        datasourceId: planForm.source.datasourceId || undefined,
        database: planForm.source.database || undefined,
      },
      target: {
        type: planForm.target.type,
        table: planForm.target.table.trim() || undefined,
        query: planForm.target.query.trim() || undefined,
        datasourceId: planForm.target.datasourceId || undefined,
        database: planForm.target.database || undefined,
      },
      keys: compareForm.keyMappings.map((m) => m.source).filter(Boolean),
      comparison: {},
      executionOptions: {
        timeoutMs: runForm.timeoutMs || undefined,
        bisectionFactor: advForm.bisectionFactor,
        bisectionThreshold: advForm.bisectionThreshold,
        checksumAlgorithm: advForm.algorithm,
        localCompareMode: advForm.localCompareMode,
        validateUniqueKeys: advForm.validateUniqueKeys,
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

  const summary = computed(() => [
    { label: "目标描述", value: planForm.goal },
    {
      label: "源端",
      value: `${planForm.source.type} · ${planForm.source.table || "(自定义查询)"}`,
    },
    {
      label: "目标端",
      value: `${planForm.target.type} · ${planForm.target.table || "(自定义查询)"}`,
    },
    { label: "主键列", value: compareForm.keyMappings.filter((m) => m.source).map((m) => m.source).join(", ") || "-" },
    { label: "超时时间", value: `${runForm.timeoutMs} ms` },
    { label: "试运行", value: runForm.dryRun ? "开启" : "关闭" },
  ]);

  return { buildConfig, configDisplay, summary };
}
