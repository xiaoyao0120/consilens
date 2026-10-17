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
    loadSideDatabases(side);
  }

  // 数据源 → 数据库列表
  async function loadSideDatabases(side) {
    const datasource = datasources.value.find((item) => item.id === planForm[side].datasourceId);
    planForm[side].databases = [];
    if (!datasource) return;
    planForm[side].databasesLoading = true;
    try {
      planForm[side].databases = (await listDatasourceDatabases(datasource.id)) || [];
    } catch (error) {
      message.error(error?.response?.data?.error || `获取 ${side} 数据库列表失败`);
    } finally {
      planForm[side].databasesLoading = false;
    }
  }

  // 数据库 → 表列表（级联）
  async function handleSideDatabaseChange(side) {
    const datasource = datasources.value.find((item) => item.id === planForm[side].datasourceId);
    planForm[side].tables = [];
    planForm[side].table = "";
    planForm[side].columns = [];
    if (!datasource || !planForm[side].database) return;
    planForm[side].tablesLoading = true;
    try {
      planForm[side].tables = (await listDatasourceTables(datasource.id, planForm[side].database)) || [];
    } catch (error) {
      message.error(error?.response?.data?.error || `获取 ${side} 表列表失败`);
    } finally {
      planForm[side].tablesLoading = false;
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
    if (!datasource || !planForm[side].database || !planForm[side].table) {
      planForm[side].columns = [];
      return;
    }
    planForm[side].columnsLoading = true;
    try {
      planForm[side].columns = (await listDatasourceColumns(
        datasource.id,
        planForm[side].database,
        planForm[side].table
      )) || [];
    } catch (error) {
      planForm[side].columns = [];
      message.error(error?.response?.data?.error || `获取 ${side} 字段失败`);
    } finally {
      planForm[side].columnsLoading = false;
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
