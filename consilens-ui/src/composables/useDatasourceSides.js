import { computed, ref } from "vue";
import {
  listDatasources,
  listDatasourceDatabases,
  listDatasourceTables,
  listDatasourceColumns,
} from "@/api/modules";

// ===== Step 1: 数据源选择与级联加载 =====
export function useDatasourceSides({ planForm, message }) {
  // 数据源选择（来自 /v1/datasources）
  const datasources = ref([]);
  const datasourcesLoaded = ref(false);
  // 每个 side 的级联加载共用序号：数据源/库/表快速切换时，较慢的旧响应不能覆盖新状态
  const requestSeq = { source: 0, target: 0 };

  async function loadDatasources(force = false) {
    if (datasourcesLoaded.value && !force) return;
    try {
      datasources.value = (await listDatasources()) || [];
    } catch {
      datasources.value = [];
    } finally {
      datasourcesLoaded.value = true;
    }
  }

  const datasourceOptions = computed(() =>
    datasources.value.map((item) => ({
      label: `${item.name}（${item.type}）`,
      value: item.id,
    }))
  );

  function handleDatasourceChange(side) {
    const selected = datasources.value.find((item) => item.id === planForm[side].datasourceId);
    if (selected) {
      planForm[side].type = selected.type;
    }
    planForm[side].database = "";
    planForm[side].table = "";
    planForm[side].query = "";
    planForm[side].tables = [];
    planForm[side].columns = [];
    // 旧的表/字段请求会被下面的 loadSideDatabases 递增序号作废；它们自己的
    // finally 因序号不匹配不会再清 loading，必须在作废点同步复位。
    planForm[side].tablesLoading = false;
    planForm[side].columnsLoading = false;
    loadSideDatabases(side);
  }

  // 数据源 → 数据库列表
  async function loadSideDatabases(side) {
    const datasource = datasources.value.find((item) => item.id === planForm[side].datasourceId);
    const seq = ++requestSeq[side];
    planForm[side].databases = [];
    planForm[side].databasesLoading = false;
    if (!datasource) return;
    planForm[side].databasesLoading = true;
    try {
      const result = await listDatasourceDatabases(datasource.id);
      if (seq !== requestSeq[side]) return;
      planForm[side].databases = result || [];
    } catch (error) {
      if (seq !== requestSeq[side]) return;
      message.error(error?.response?.data?.error || `获取 ${side} 数据库列表失败`);
    } finally {
      if (seq === requestSeq[side]) {
        planForm[side].databasesLoading = false;
      }
    }
  }

  // 数据库 → 表列表（级联）
  async function handleSideDatabaseChange(side) {
    const datasource = datasources.value.find((item) => item.id === planForm[side].datasourceId);
    const seq = ++requestSeq[side];
    planForm[side].tables = [];
    planForm[side].table = "";
    planForm[side].columns = [];
    planForm[side].tablesLoading = false;
    planForm[side].columnsLoading = false;
    if (!datasource || !planForm[side].database) return;
    planForm[side].tablesLoading = true;
    try {
      const result = await listDatasourceTables(datasource.id, planForm[side].database);
      if (seq !== requestSeq[side]) return;
      planForm[side].tables = result || [];
    } catch (error) {
      if (seq !== requestSeq[side]) return;
      message.error(error?.response?.data?.error || `获取 ${side} 表列表失败`);
    } finally {
      if (seq === requestSeq[side]) {
        planForm[side].tablesLoading = false;
      }
    }
  }

  // 表选中后加载字段（Step2 使用）
  function handleSideTableChange(side) {
    planForm[side].columns = [];
    loadSideColumns(side);
  }

  // 从数据源 + 库 + 表实时获取字段
  async function loadSideColumns(side) {
    const datasource = datasources.value.find((item) => item.id === planForm[side].datasourceId);
    const seq = ++requestSeq[side];
    planForm[side].columnsLoading = false;
    if (!datasource || !planForm[side].database || !planForm[side].table) {
      planForm[side].columns = [];
      return;
    }
    planForm[side].columnsLoading = true;
    try {
      const result = await listDatasourceColumns(
        datasource.id,
        planForm[side].database,
        planForm[side].table
      );
      if (seq !== requestSeq[side]) return;
      planForm[side].columns = result || [];
    } catch (error) {
      if (seq !== requestSeq[side]) return;
      planForm[side].columns = [];
      message.error(error?.response?.data?.error || `获取 ${side} 字段失败`);
    } finally {
      if (seq === requestSeq[side]) {
        planForm[side].columnsLoading = false;
      }
    }
  }

  const sourceColumnOptions = computed(() =>
    planForm.source.columns.map((column) => ({
      label: `${column.name}（${column.dataType || "?"}）`,
      value: column.name,
    }))
  );

  const targetColumnOptions = computed(() =>
    planForm.target.columns.map((column) => ({
      label: `${column.name}（${column.dataType || "?"}）`,
      value: column.name,
    }))
  );

  const ignoreColumnOptions = computed(() => {
    const names = new Set([
      ...planForm.source.columns.map((c) => c.name),
      ...planForm.target.columns.map((c) => c.name),
    ]);
    return [...names].map((name) => ({ label: name, value: name }));
  });

  // 编辑回显：按数据源引用加载数据库 → 表 → 字段列表
  async function restoreSideSelects(side) {
    await loadSideDatabases(side);
    if (planForm[side].database) {
      // handleSideDatabaseChange 会清空 table，先暂存后恢复
      const savedTable = planForm[side].table;
      const savedQuery = planForm[side].query;
      await handleSideDatabaseChange(side);
      planForm[side].table = savedTable;
      planForm[side].query = savedQuery;
    }
    if (planForm[side].mode === "table" && planForm[side].table) {
      loadSideColumns(side);
    }
  }

  return {
    datasources,
    datasourceOptions,
    loadDatasources,
    handleDatasourceChange,
    loadSideDatabases,
    handleSideDatabaseChange,
    handleSideTableChange,
    loadSideColumns,
    sourceColumnOptions,
    targetColumnOptions,
    ignoreColumnOptions,
    restoreSideSelects,
  };
}
