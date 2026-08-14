<script setup>
import { ref, reactive, computed, watch, nextTick } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useMessage } from "naive-ui";
import { NButton, NIcon, NInput, NInputNumber, NSelect, NDynamicTags, NSwitch, NSpin, NCheckbox, NRadioGroup, NRadioButton, NModal } from "naive-ui";
import { ArrowBackOutline, ArrowForwardOutline, CheckmarkCircleOutline, GitCompareOutline, RefreshOutline, SaveOutline } from "@vicons/ionicons5";
import { createTaskDefinition, updateTaskDefinition, getTaskDefinition, runTaskDefinition, listDatasources, listDatasourceDatabases, listDatasourceTables, listDatasourceColumns, listDatasourceTypes, getDatasourceTypeConfig, createDatasource, testConnection } from "@/api/modules";
import { generateSerialNo } from "@/utils/format";
import DataSourceForm from "@/components/DataSourceForm.vue";
import { FALLBACK_DS_FIELDS, collectDsParam, splitTestOptions } from "@/common/datasourceDefaults";

const router = useRouter();
const route = useRoute();
const message = useMessage();

const currentStep = ref(1);
const submitting = ref(false);
const planning = ref(false);
const planError = ref("");

const steps = [
  { title: "数据源", desc: "源端与目标端信息" },
  { title: "比对键", desc: "对齐两表的键列" },
  { title: "高级选项", desc: "超时与试运行" },
  { title: "确认", desc: "生成方案并提交" },
];

// ===== Step 1: 数据源 =====
const TYPE_OPTIONS = [
  { label: "MySQL", value: "mysql" },
  { label: "PostgreSQL", value: "postgres" },
  { label: "SQL Server", value: "sqlserver" },
  { label: "Oracle", value: "oracle" },
];

function makeSide() {
  return {
    datasourceId: null,
    type: "mysql",
    mode: "table", // table | sql
    table: "",
    query: "",
    database: "",
    databases: [],
    tables: [],
    databasesLoading: false,
    tablesLoading: false,
    columns: [],
    columnsLoading: false,
  };
}

const planForm = reactive({
  goal: "生成源表与目标表的数据对比配置",
  source: makeSide(),
  target: makeSide(),
  keys: [],
});

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

// ===== 数据源快捷创建弹框 =====
const dsModal = reactive({
  show: false,
  side: null,
  saving: false,
  testing: false,
  testResult: null,
  types: [],
  fields: [], // 当前类型的连接参数模板（来自后端）
  values: {}, // 模板字段值（字符串）
  form: {
    name: "",
    type: "mysql",
  },
});

// 动态表单实例（validateAndGetValues / setValues）
const dsFormRef = ref(null);

// 拉取类型模板：失败时使用兜底模板；序号防止快速切换类型时竞态覆盖
let dsConfigSeq = 0;
async function loadDsConfig(type) {
  const seq = ++dsConfigSeq;
  dsModal.fields = [];
  dsModal.values = {};
  let fields = FALLBACK_DS_FIELDS;
  try {
    const config = await getDatasourceTypeConfig(type);
    if (seq !== dsConfigSeq) return;
    fields = Array.isArray(config) && config.length ? config : FALLBACK_DS_FIELDS;
  } catch {
    if (seq !== dsConfigSeq) return;
  }
  dsModal.fields = fields;
  await nextTick();
  if (seq === dsConfigSeq) {
    dsFormRef.value?.setValues({});
  }
}

// 切换类型：重置模板与表单值
watch(
  () => dsModal.form.type,
  (type) => {
    if (dsModal.show && type) loadDsConfig(type);
  }
);

async function openDsModal(side) {
  dsModal.side = side;
  dsModal.testResult = null;
  dsModal.form = { name: "", type: "mysql" };
  if (!dsModal.types.length) {
    try {
      dsModal.types = (await listDatasourceTypes()) || [];
    } catch {
      dsModal.types = [];
    }
  }
  const defaultType = dsModal.types.find((t) => t.type === "mysql")?.type || dsModal.types[0]?.type || "mysql";
  dsModal.form.type = defaultType;
  dsModal.show = true;
  // type 未变化时 watch 不触发，这里显式加载模板
  loadDsConfig(defaultType);
}

const dsTypeOptions = computed(() =>
  dsModal.types.map((item) => ({ label: `${item.type}（默认端口 ${item.defaultPort}）`, value: item.type }))
);

async function handleDsTest() {
  const { valid, values } = (await dsFormRef.value?.validateAndGetValues()) || { valid: false, values: null };
  if (!valid) return;
  dsModal.testing = true;
  dsModal.testResult = null;
  try {
    // 仅当字段有值才放入；sid / schema / properties 放入 options
    const param = collectDsParam(values, dsModal.fields);
    const options = splitTestOptions(param);
    const result = await testConnection({
      type: dsModal.form.type,
      ...param,
      options,
    });
    dsModal.testResult = { ok: result.success, text: result.error || "连接成功" };
  } catch (error) {
    dsModal.testResult = { ok: false, text: error?.response?.data?.error || "连接失败" };
  } finally {
    dsModal.testing = false;
  }
}

async function handleDsCreate() {
  if (!dsModal.form.name.trim()) {
    message.warning("请填写名称");
    return;
  }
  const { valid, values } = (await dsFormRef.value?.validateAndGetValues()) || { valid: false, values: null };
  if (!valid) return;
  dsModal.saving = true;
  try {
    const created = await createDatasource({
      name: dsModal.form.name.trim(),
      type: dsModal.form.type,
      param: collectDsParam(values, dsModal.fields),
    });
    message.success("数据源已创建");
    dsModal.show = false;
    // 刷新下拉并自动选中新数据源
    await loadDatasources(true);
    if (dsModal.side) {
      planForm[dsModal.side].datasourceId = created.id;
      handleDatasourceChange(dsModal.side);
    }
  } catch (error) {
    message.error(error?.response?.data?.error || "创建失败");
  } finally {
    dsModal.saving = false;
  }
}

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

// ===== Step 2: 字段加载与映射 =====
const compareForm = reactive({
  keyMappings: [], // [{source, target}] 主键映射
  fieldMappings: [], // [{source, target}] 比较字段映射
  ignoreColumns: [], // [name] 排除字段
});

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

function setMapping(listName, index, field, value) {
  compareForm[listName][index][field] = value;
}

function addMapping(listName, source, target) {
  compareForm[listName].push({ source: source || "", target: target || "" });
}

function removeMapping(listName, index) {
  compareForm[listName].splice(index, 1);
}

// 自动匹配两表同名字段作为比较字段（排除主键列）
function autoMatchFields() {
  const keyNames = new Set(compareForm.keyMappings.map((m) => m.source).filter(Boolean));
  const sourceNames = new Set(planForm.source.columns.map((c) => c.name));
  const targetNames = new Set(planForm.target.columns.map((c) => c.name));
  const matched = [...sourceNames].filter((name) => targetNames.has(name) && !keyNames.has(name));
  compareForm.fieldMappings = matched.map((name) => ({ source: name, target: name }));
}

// 自动把同名主键填入（优先 id，其次第一个同名字段；只取一列避免全选）
function autoMatchKeys() {
  const sourceNames = planForm.source.columns.map((c) => c.name);
  const targetNames = new Set(planForm.target.columns.map((c) => c.name));
  const matched = sourceNames.filter((name) => targetNames.has(name));
  if (!matched.length) {
    message.warning("两表没有同名字段，请手动选择主键");
    return;
  }
  const primary = matched.includes("id") ? "id" : matched[0];
  compareForm.keyMappings = [{ source: primary, target: primary }];
}

// ===== Step 3: 高级选项 =====
const runForm = reactive({
  serialNo: generateSerialNo(),
  timeoutMs: 300000,
  dryRun: false,
});

function regenerateSerialNo() {
  runForm.serialNo = generateSerialNo();
}

// ===== 比对策略高级选项（对齐 CLI strategy 配置）=====
const advForm = reactive({
  mode: "checksum",          // strategy.mode: checksum | join
  algorithm: "concat",       // strategy.algorithm / checksumAlgorithm
  bisectionFactor: 4,        // strategy.bisectionFactor
  bisectionThreshold: 5000,  // strategy.bisectionThreshold
  batchSize: 1000,           // strategy.batchSize（透传 attributes）
  localCompareMode: "full",  // strategy.localCompare.mode: full | auto
  validateUniqueKeys: true,  // 校验唯一键
  maxDifferences: null,      // 最大差异数（可选）
  enableProfiling: false,    // 性能分析
});

// ===== Step 4: 确认（Plan → Validate → Execute）=====
function sideHasInput(side) {
  if (planForm[side].mode === "sql") {
    return Boolean(planForm[side].query.trim());
  }
  return Boolean(planForm[side].table.trim());
}

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

// ===== Step 4: 保存定义 =====
const defForm = reactive({
  name: "",
  description: "",
  runNow: true,
});

// ===== 编辑模式（?editId=）=====
const editId = ref(route.query.editId || "");

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

async function loadForEdit() {
  if (!editId.value) return;
  try {
    const definition = await getTaskDefinition(editId.value);
    const config = definition.config || {};
    planForm.goal = config.goal || planForm.goal;
    const fillSide = (side, endpoint) => {
      if (!endpoint) return;
      planForm[side].type = endpoint.type || "mysql";
      planForm[side].table = endpoint.table || "";
      planForm[side].query = endpoint.query || "";
      planForm[side].database = endpoint.database || "";
      planForm[side].datasourceId = endpoint.datasourceId != null ? String(endpoint.datasourceId) : null;
      planForm[side].mode = endpoint.table ? "table" : "sql";
    };
    fillSide("source", config.source);
    fillSide("target", config.target);
    // 级联下拉回显：加载数据库/表/字段列表（数据源引用 + 库表名已就位）
    await Promise.all([
      restoreSideSelects("source"),
      restoreSideSelects("target"),
    ]);
    compareForm.keyMappings = (config.keys || []).map((key) => ({ source: key, target: key }));
    const comparison = config.comparison || {};
    if (comparison.fieldMappings?.length) {
      compareForm.fieldMappings = comparison.fieldMappings.map((m) => ({ source: m.source, target: m.target }));
    } else if (comparison.fields?.length) {
      compareForm.fieldMappings = comparison.fields.map((field) => ({ source: field, target: field }));
    }
    compareForm.ignoreColumns = comparison.ignoreColumns || [];
    const exec = config.executionOptions || {};
    const strategyPref = config.hints?.strategyPreference || {};
    advForm.mode = (strategyPref.preferredPlans?.[0] || "CHECKSUM").toLowerCase();
    advForm.algorithm = exec.checksumAlgorithm || "concat";
    advForm.bisectionFactor = exec.bisectionFactor ?? 4;
    advForm.bisectionThreshold = exec.bisectionThreshold ?? 5000;
    advForm.batchSize = exec.batchSize ?? 1000;
    advForm.localCompareMode = exec.localCompareMode || "full";
    advForm.validateUniqueKeys = exec.validateUniqueKeys ?? true;
    advForm.maxDifferences = exec.maxDifferences ?? null;
    advForm.enableProfiling = exec.enableProfiling ?? false;
    defForm.name = definition.name;
    defForm.description = definition.description || "";
    // 编辑默认回到第一步，便于逐项确认与修改
    currentStep.value = 1;
    if (!planForm.source.datasourceId && !planForm.target.datasourceId) {
      message.info("该定义未引用数据源，请回到第一步选择数据源以完成字段配置");
    }
  } catch (error) {
    message.error(error?.response?.data?.error || "加载定义失败");
  }
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
      enableProfiling: advForm.enableProfiling,
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

const configDisplay = computed(() => JSON.stringify(buildConfig(), null, 2));

async function handleSaveDefinition() {
  if (!defForm.name.trim()) {
    message.warning("请填写定义名称");
    return;
  }
  if (!compareForm.keyMappings.length) {
    message.warning("请至少配置一个主键列");
    return;
  }
  submitting.value = true;
  try {
    const payload = {
      name: defForm.name.trim(),
      description: defForm.description.trim() || undefined,
      config: buildConfig(),
    };
    const definition = editId.value
      ? await updateTaskDefinition(editId.value, payload)
      : await createTaskDefinition(payload);
    if (defForm.runNow) {
      const accepted = await runTaskDefinition(definition.id);
      message.success(`${editId.value ? "已更新定义" : "已保存定义"}并提交运行：${accepted.taskId}`);
      router.push(`/instances/${accepted.taskId}`);
    } else {
      message.success(`定义「${definition.name}」已${editId.value ? "更新" : "保存"}`);
      router.push(`/definitions/${definition.id}`);
    }
  } catch (error) {
    message.error(error?.response?.data?.error || "保存失败");
  } finally {
    submitting.value = false;
  }
}

async function goNext() {
  if (currentStep.value === 1) {
    if (!planForm.source.datasourceId || !planForm.target.datasourceId) {
      message.warning("请为两端选择数据源");
      return;
    }
    if (!sideHasInput("source") || !sideHasInput("target")) {
      message.warning("请填写两端表名或查询");
      return;
    }
  } else if (currentStep.value === 2) {
    if (!compareForm.keyMappings.length) {
      message.warning("请至少配置一个主键列");
      return;
    }
  } else if (currentStep.value === 3) {
    // 名称在确认页填写；handleSaveDefinition 中校验
  }
  currentStep.value += 1;
  if (currentStep.value === 2) {
    // 进入比对键页：加载两端字段
    loadSideColumns("source");
    loadSideColumns("target");
  }
}

function goPrev() {
  if (currentStep.value > 1) currentStep.value -= 1;
}

// 允许点击跳回已完成的步骤
function clickStep(index) {
  if (index + 1 < currentStep.value) {
    currentStep.value = index + 1;
  }
}

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

// 进入页面时预加载数据源列表；编辑模式加载定义
loadDatasources();
if (editId.value) {
  loadForEdit();
}
</script>

<template>
  <div class="page-container">
    <div class="wizard-wrap">
      <!-- 页头 -->
      <div class="wizard-head">
        <a class="back-link" @click="router.push('/definitions')">
          <n-icon size="14"><ArrowBackOutline /></n-icon>
          返回定义列表
        </a>
        <h1 class="wizard-title">{{ editId ? "编辑定义" : "新建定义" }}</h1>
        <p class="wizard-subtitle">四步保存一个可复用的数据校验任务定义</p>
      </div>

      <!-- 步骤条 -->
      <div class="stepper">
        <template v-for="(step, index) in steps" :key="step.title">
          <div v-if="index > 0" class="stepper-line" :class="{ filled: currentStep > index }" />
          <div
            class="stepper-item"
            :class="{ active: currentStep === index + 1, done: currentStep > index + 1 }"
            @click="clickStep(index)"
          >
            <div class="stepper-dot">
              <n-icon v-if="currentStep > index + 1" size="12"><CheckmarkCircleOutline /></n-icon>
              <span v-else>{{ index + 1 }}</span>
            </div>
            <div class="stepper-text">
              <div class="stepper-title">{{ step.title }}</div>
              <div class="stepper-desc">{{ step.desc }}</div>
            </div>
          </div>
        </template>
      </div>

      <!-- Step 1: 数据源 -->
      <div v-show="currentStep === 1" class="panel">
        <div class="panel-head">
          <h2 class="panel-title">对比目标</h2>
          <p class="panel-desc">描述本次比对的目的，用于生成配置</p>
        </div>
        <div class="field">
          <label class="field-label">目标描述</label>
          <n-input v-model:value="planForm.goal" placeholder="例如：生成源表与目标表的数据对比配置" />
        </div>

        <div class="side-grid">
          <div v-for="side in ['source', 'target']" :key="side" class="side-card">
            <div class="side-head">
              <span class="side-badge" :class="side">{{ side === "source" ? "源" : "目标" }}</span>
              <span class="side-name">{{ side === "source" ? "源端" : "目标端" }}</span>
              <span class="side-code">{{ side }}</span>
            </div>
            <div class="field">
              <label class="field-label">数据源</label>
              <div class="inline-row">
                <n-select
                  v-model:value="planForm[side].datasourceId"
                  :options="datasourceOptions"
                  placeholder="选择已配置的数据源"
                  class="flex-1"
                  @update:value="handleDatasourceChange(side)"
                />
                <n-button class="btn-ghost btn-sm" @click="openDsModal(side)" title="新建数据源">+ 添加</n-button>
              </div>
            </div>
            <div class="field">
              <label class="field-label">数据范围</label>
              <n-radio-group v-model:value="planForm[side].mode" size="small">
                <n-radio-button value="table">表</n-radio-button>
                <n-radio-button value="sql">自定义 SQL</n-radio-button>
              </n-radio-group>
            </div>
            <template v-if="planForm[side].mode === 'table'">
              <div class="field">
                <label class="field-label">数据库</label>
                <n-select
                  v-model:value="planForm[side].database"
                  :options="planForm[side].databases.map((name) => ({ label: name, value: name }))"
                  :loading="planForm[side].databasesLoading"
                  :disabled="!planForm[side].datasourceId"
                  placeholder="选择数据库"
                  @update:value="handleSideDatabaseChange(side)"
                />
              </div>
              <div class="field">
                <label class="field-label">表</label>
                <n-select
                  v-model:value="planForm[side].table"
                  :options="planForm[side].tables.map((name) => ({ label: name, value: name }))"
                  :loading="planForm[side].tablesLoading"
                  :disabled="!planForm[side].database"
                  placeholder="选择表"
                  @update:value="handleSideTableChange(side)"
                />
              </div>
            </template>
            <template v-else>
              <div class="field">
                <label class="field-label">数据库</label>
                <n-select
                  v-model:value="planForm[side].database"
                  :options="planForm[side].databases.map((name) => ({ label: name, value: name }))"
                  :loading="planForm[side].databasesLoading"
                  :disabled="!planForm[side].datasourceId"
                  placeholder="选择数据库（用于连接 URL）"
                />
              </div>
              <div class="field">
                <label class="field-label">自定义查询</label>
                <n-input
                  v-model:value="planForm[side].query"
                  type="textarea"
                  :rows="3"
                  placeholder="SELECT ... FROM db.table WHERE ..."
                />
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- Step 2: 比对键与字段 -->
      <div v-show="currentStep === 2" class="panel">
        <div class="panel-head">
          <h2 class="panel-title">比对键与字段</h2>
          <p class="panel-desc">配置主键对齐、比较字段一一对应与排除字段</p>
        </div>
        <n-spin :show="planForm.source.columnsLoading || planForm.target.columnsLoading">
          <div class="section">
            <div class="section-head">
              <div class="section-title">主键列</div>
              <div class="section-tip">用于对齐两表行：源端缺失记为新增，目标端缺失记为删除</div>
            </div>
            <div v-if="!compareForm.keyMappings.length" class="empty-tip">尚未配置主键，点击「自动匹配同名字段」或手动添加</div>
            <div v-for="(mapping, index) in compareForm.keyMappings" :key="'k' + index" class="mapping-row">
              <n-select :value="mapping.source" :options="sourceColumnOptions" placeholder="源表主键列" filterable @update:value="(value) => setMapping('keyMappings', index, 'source', value)" />
              <span class="mapping-arrow">→</span>
              <n-select :value="mapping.target" :options="targetColumnOptions" placeholder="目标表主键列" filterable @update:value="(value) => setMapping('keyMappings', index, 'target', value)" />
              <n-button class="btn-ghost btn-icon" @click="removeMapping('keyMappings', index)">移除</n-button>
            </div>
            <div class="section-actions">
              <n-button class="btn-ghost btn-sm" @click="autoMatchKeys">自动匹配同名字段</n-button>
              <n-button class="btn-ghost btn-sm" @click="addMapping('keyMappings')">+ 添加主键</n-button>
            </div>
          </div>
          <div class="section">
            <div class="section-head">
              <div class="section-title">比较字段</div>
              <div class="section-tip">逐列一一对应比较；两端列名不同时手动指定映射</div>
            </div>
            <div v-if="!compareForm.fieldMappings.length" class="empty-tip">未配置比较字段，默认比较两表全部非主键列；点击「自动匹配同名字段」快速生成</div>
            <div v-for="(mapping, index) in compareForm.fieldMappings" :key="'f' + index" class="mapping-row">
              <n-select :value="mapping.source" :options="sourceColumnOptions" placeholder="源表字段" filterable @update:value="(value) => setMapping('fieldMappings', index, 'source', value)" />
              <span class="mapping-arrow">→</span>
              <n-select :value="mapping.target" :options="targetColumnOptions" placeholder="目标表字段" filterable @update:value="(value) => setMapping('fieldMappings', index, 'target', value)" />
              <n-button class="btn-ghost btn-icon" @click="removeMapping('fieldMappings', index)">移除</n-button>
            </div>
            <div class="section-actions">
              <n-button class="btn-ghost btn-sm" @click="autoMatchFields">自动匹配同名字段</n-button>
              <n-button class="btn-ghost btn-sm" @click="addMapping('fieldMappings')">+ 添加映射</n-button>
            </div>
          </div>
          <div class="section">
            <div class="section-head">
              <div class="section-title">排除字段</div>
              <div class="section-tip">这些字段不参与比较（如 updated_at、etl 标记列）</div>
            </div>
            <n-select v-model:value="compareForm.ignoreColumns" :options="ignoreColumnOptions" multiple filterable placeholder="选择要排除的字段（两端同名字段都会被排除）" />
          </div>
          <div class="hint-box">
            <span class="hint-title">提示</span>
            <p class="hint-body">主键与比较字段来自两端表结构的<b>实时读取</b>；主键用于对齐行，比较字段按位置一一对应比较，排除字段在两侧按列名过滤。</p>
          </div>
        </n-spin>
      </div>

      <!-- Step 3: 高级选项 -->
      <div v-show="currentStep === 3" class="panel">
        <div class="panel-head">
          <h2 class="panel-title">高级选项</h2>
          <p class="panel-desc">任务序号、超时与运行模式</p>
        </div>
        <div class="section">
          <div class="section-head">
            <div class="section-title">比对策略</div>
            <div class="section-tip">对齐 CLI strategy 配置，控制比对引擎行为</div>
          </div>
          <div class="adv-grid">
            <div class="field">
              <label class="field-label">比对模式</label>
              <n-select v-model:value="advForm.mode" :options="[
                { label: 'checksum（默认）', value: 'checksum' },
                { label: 'join', value: 'join' },
              ]" />
            </div>
            <div class="field">
              <label class="field-label">校验算法</label>
              <n-select v-model:value="advForm.algorithm" :options="[
                { label: 'concat（拼接后 MD5，默认）', value: 'concat' },
                { label: 'xor（XOR 聚合，顺序不敏感）', value: 'xor' },
              ]" />
            </div>
            <div class="field">
              <label class="field-label">分段因子</label>
              <n-input-number v-model:value="advForm.bisectionFactor" :min="1" :max="64" class="flex-1" />
            </div>
            <div class="field">
              <label class="field-label">分段阈值</label>
              <n-input-number v-model:value="advForm.bisectionThreshold" :min="100" :step="1000" class="flex-1" />
            </div>
            <div class="field">
              <label class="field-label">批大小</label>
              <n-input-number v-model:value="advForm.batchSize" :min="1" :step="100" class="flex-1" />
            </div>
            <div class="field">
              <label class="field-label">本地比对模式</label>
              <n-select v-model:value="advForm.localCompareMode" :options="[
                { label: 'full', value: 'full' },
                { label: 'auto', value: 'auto' },
              ]" />
            </div>
            <div class="field switch-field">
              <div class="switch-text">
                <span class="field-label">校验唯一键</span>
                <span class="field-desc">比对前验证主键唯一性</span>
              </div>
              <n-switch v-model:value="advForm.validateUniqueKeys" />
            </div>
            <div class="field switch-field">
              <div class="switch-text">
                <span class="field-label">性能分析</span>
                <span class="field-desc">记录比对性能指标</span>
              </div>
              <n-switch v-model:value="advForm.enableProfiling" />
            </div>
            <div class="field">
              <label class="field-label">最大差异数</label>
              <n-input-number v-model:value="advForm.maxDifferences" :min="1" :step="1000" class="flex-1" placeholder="不限制" />
            </div>
          </div>
        </div>
        <div class="field">
          <label class="field-label">任务序号</label>
          <div class="inline-row">
            <n-input v-model:value="runForm.serialNo" class="flex-1" />
            <n-button class="btn-ghost btn-sm" @click="regenerateSerialNo">重新生成</n-button>
          </div>
        </div>
        <div class="field">
          <label class="field-label">超时时间</label>
          <div class="inline-row">
            <n-input-number v-model:value="runForm.timeoutMs" :min="1000" :step="1000" class="flex-1" />
            <span class="unit-hint">毫秒</span>
          </div>
        </div>
        <div class="field switch-field">
          <div class="switch-text">
            <span class="field-label">试运行</span>
            <span class="field-desc">开启后仅验证执行链路，不产生运行结果</span>
          </div>
          <n-switch v-model:value="runForm.dryRun" />
        </div>
      </div>

      <!-- Step 4: 保存定义 -->
      <div v-show="currentStep === 4" class="panel">
        <div class="panel-head">
          <h2 class="panel-title">保存定义</h2>
          <p class="panel-desc">核对配置，保存为可复用的任务定义</p>
        </div>
        <div class="field">
          <label class="field-label">定义名称 <span class="required">*</span></label>
          <n-input v-model:value="defForm.name" placeholder="例如：订单表每日比对" maxlength="128" />
        </div>
        <div class="field">
          <label class="field-label">描述</label>
          <n-input v-model:value="defForm.description" placeholder="可选，说明该定义用途" maxlength="512" />
        </div>
        <div class="field">
          <n-checkbox v-model:checked="defForm.runNow">保存后立即运行一次</n-checkbox>
        </div>
        <div class="summary-list">
          <div v-for="item in summary" :key="item.label" class="summary-row">
            <span class="summary-label">{{ item.label }}</span>
            <span class="summary-value">{{ item.value }}</span>
          </div>
        </div>
        <div class="code-block">
          <div class="code-head">
            <span>运行配置（保存到定义）</span>
          </div>
          <pre class="code-body">{{ configDisplay }}</pre>
        </div>
      </div>

      <!-- 底部操作栏（固定，卡片内部滚动） -->
      <div class="step-bar">
        <div class="step-bar-inner">
          <n-button v-if="currentStep > 1" class="btn-ghost" @click="goPrev">
            <template #icon>
              <n-icon><ArrowBackOutline /></n-icon>
            </template>
            上一步
          </n-button>
          <div class="flex-1" />
          <template v-if="currentStep < 4">
            <n-button class="btn-primary" @click="goNext">
              下一步
              <template #icon>
                <n-icon><ArrowForwardOutline /></n-icon>
              </template>
            </n-button>
          </template>
          <template v-else>
            <n-button class="btn-primary" :loading="submitting" @click="handleSaveDefinition">
              <template #icon>
                <n-icon><SaveOutline /></n-icon>
              </template>
              {{ defForm.runNow ? "保存并运行" : "保存定义" }}
            </n-button>
          </template>
        </div>
      </div>
      </div>

      <!-- 新建数据源弹框 -->
      <n-modal v-model:show="dsModal.show" preset="card" style="width: 640px" title="新建数据源">
        <div class="ds-form">
          <div class="ds-row-top">
            <div class="field">
              <label class="field-label">名称</label>
              <n-input v-model:value="dsModal.form.name" placeholder="例如：生产订单库" maxlength="128" />
            </div>
            <div class="field">
              <label class="field-label">类型</label>
              <n-select v-model:value="dsModal.form.type" :options="dsTypeOptions" />
            </div>
          </div>
          <div class="ds-separator" />
          <data-source-form
            ref="dsFormRef"
            v-model:model-value="dsModal.values"
            :fields="dsModal.fields"
          />
          <div v-if="dsModal.fields.length === 0" class="field text-muted" style="font-size: 12px">
            连接参数加载中…
          </div>
        </div>
        <template #footer>
          <div class="modal-footer">
            <div class="modal-footer-left">
              <n-button class="btn-ghost btn-sm" :loading="dsModal.testing" @click="handleDsTest">测试连接</n-button>
              <span v-if="dsModal.testResult" class="conn-result" :class="dsModal.testResult.ok ? 'ok' : 'fail'">
                <span class="conn-dot" />{{ dsModal.testResult.text }}
              </span>
            </div>
            <div class="modal-footer-right">
              <n-button class="btn-ghost" @click="dsModal.show = false">取消</n-button>
              <n-button class="btn-primary" :loading="dsModal.saving" @click="handleDsCreate">创建</n-button>
            </div>
          </div>
        </template>
      </n-modal>
  </div>
</template>

<style scoped lang="scss">
// ===== 页面骨架：宽栏居中，滚动限制在步骤卡片内部 =====
.page-container {
  height: 100vh;
  box-sizing: border-box;
  overflow: hidden;
}

.wizard-wrap {
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.wizard-head {
  flex-shrink: 0;
  margin-bottom: 16px;

  .back-link {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 13px;
    color: var(--text-secondary);
    cursor: pointer;
    text-decoration: none;

    &:hover {
      color: var(--text);
    }
  }

  .wizard-title {
    margin: 12px 0 4px;
    font-size: 20px;
    font-weight: 600;
    color: var(--text);
    letter-spacing: -0.01em;
  }

  .wizard-subtitle {
    margin: 0;
    font-size: 13px;
    color: var(--text-secondary);
  }
}

// ===== 步骤条 =====
.stepper {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  padding: 20px 24px;
  margin-bottom: 16px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--card-shadow);
}

.stepper-item {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.stepper-line {
  flex: 1;
  height: 2px;
  margin: 0 6px;
  align-self: center;
  background: var(--border);
  border-radius: 1px;
  transition: background 0.2s ease;

  &.filled {
    background: var(--primary);
  }
}

.stepper-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
  background: var(--bg);
  border: 1px solid var(--border);

  .stepper-item.active & {
    color: #fff;
    background: var(--primary);
    border-color: var(--primary);
  }

  .stepper-item.done & {
    color: var(--primary);
    border-color: var(--primary);
  }
}

.stepper-text {
  min-width: 0;
}

.stepper-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
  white-space: nowrap;

  .stepper-item.active & {
    color: var(--text);
    font-weight: 600;
  }
}

.stepper-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

// ===== 面板 =====
.panel {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--card-shadow);
  padding: 24px;
}

.panel-head {
  padding-bottom: 16px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--border);

  .panel-title {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    color: var(--text);
  }

  .panel-desc {
    margin: 4px 0 0;
    font-size: 12px;
    color: var(--text-secondary);
  }
}

// ===== 字段 =====
.field {
  margin-bottom: 18px;

  &:last-child {
    margin-bottom: 0;
  }
}

.field-label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

.required {
  color: var(--danger);
}

.field-desc {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-muted);
}

.inline-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.unit-hint {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}

.switch-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg);
}

.switch-text {
  .field-label {
    margin-bottom: 0;
  }
}

// ===== 源/目标双栏 =====
.side-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 4px;
}

.side-card {
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg);
}

.side-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;

  .side-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border-radius: 5px;
    font-size: 11px;
    font-weight: 600;
    color: #fff;

    &.source {
      background: var(--primary);
    }

    &.target {
      background: var(--success);
    }
  }

  .side-name {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
  }

  .side-code {
    margin-left: auto;
    font-size: 12px;
    color: var(--text-muted);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }
}

.side-card .field {
  margin-bottom: 12px;
}

// ===== 连接测试 =====
.conn-block {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--border);
}

.conn-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;

  .conn-label {
    font-size: 12px;
    font-weight: 600;
    color: var(--text-secondary);
  }

  .conn-tip {
    font-size: 11px;
    color: var(--text-muted);
  }
}

.conn-grid {
  display: grid;
  grid-template-columns: 2fr 1fr 1.4fr;
  gap: 8px;
  margin-bottom: 8px;

  &:last-of-type {
    margin-bottom: 0;
  }
}

.conn-test-btn {
  height: 34px;
  padding: 0 12px;
  font-size: 13px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: var(--card);
  color: var(--text);
  cursor: pointer;

  &:hover {
    background: var(--bg);
  }
}

.conn-result {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;

  &.ok {
    color: var(--success);

    .conn-dot {
      background: var(--success);
    }
  }

  &.fail {
    color: var(--danger);

    .conn-dot {
      background: var(--danger);
    }
  }
}

.conn-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.conn-msg {
  word-break: break-all;
}

// ===== 提示框 =====
// ===== Step 2: 比对键 =====
.section {
  margin-bottom: 22px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg);
}

.section-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;

  .section-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
  }

  .section-tip {
    font-size: 12px;
    color: var(--text-muted);
  }
}

.empty-tip {
  padding: 10px 12px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--text-muted);
  border: 1px dashed var(--border);
  border-radius: 6px;
  background: var(--card);
}

.mapping-row {
  display: grid;
  grid-template-columns: 1fr 36px 1fr 64px;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;

  &:last-of-type {
    margin-bottom: 0;
  }
}

.mapping-arrow {
  text-align: center;
  font-size: 14px;
  color: var(--text-muted);
}

.section-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.btn-icon {
  height: 34px;
  padding: 0 8px;
  font-size: 12px;
}

.hint-box {
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg);
  margin-bottom: 4px;

  .hint-title {
    font-size: 12px;
    font-weight: 600;
    color: var(--text-secondary);
  }

  .hint-body {
    margin: 4px 0 0;
    font-size: 12px;
    line-height: 1.7;
    color: var(--text-secondary);

    b {
      color: var(--text);
      font-weight: 500;
    }
  }
}

// ===== 底部操作栏（固定） =====
.step-bar {
  flex-shrink: 0;
  padding: 14px 0 0;
  border-top: 1px solid var(--border);
}

.step-bar-inner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 4px 4px;
}

// ===== 按钮（shadcn 风格）=====
.btn-primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: 6px;
  background: var(--text);
  color: var(--card);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s ease;

  &:hover {
    opacity: 0.85;
  }
}

.btn-ghost {
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--card);
  color: var(--text);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: var(--bg);
  }
}

.btn-sm {
  height: 32px;
  padding: 0 10px;
  font-size: 12px;
}

// ===== 确认页 =====
.error-banner {
  padding: 10px 14px;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--danger);
  background: rgba(220, 38, 38, 0.06);
  border: 1px solid rgba(220, 38, 38, 0.25);
  border-radius: 6px;
}

.summary-list {
  margin-bottom: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}

.summary-row {
  display: flex;
  align-items: baseline;
  padding: 10px 16px;
  font-size: 13px;

  &:nth-child(odd) {
    background: var(--bg);
  }

  .summary-label {
    flex: 0 0 100px;
    color: var(--text-muted);
  }

  .summary-value {
    flex: 1;
    color: var(--text);
    word-break: break-all;
  }
}

.code-block {
  margin-bottom: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;

  .code-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 14px;
    font-size: 12px;
    font-weight: 500;
    color: var(--text-secondary);
    background: var(--bg);
    border-bottom: 1px solid var(--border);

    .code-meta {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 11px;
      color: var(--text-muted);
    }
  }

  .code-body {
    margin: 0;
    padding: 14px;
    max-height: 320px;
    overflow: auto;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    line-height: 1.6;
    color: var(--text);
    background: var(--card);
    white-space: pre;
  }
}

.validate-result {
  margin-top: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;

  .validate-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    font-size: 13px;
    font-weight: 500;

    &.ok {
      color: var(--success);
      background: rgba(22, 163, 74, 0.06);

      .conn-dot {
        background: var(--success);
      }
    }

    &.fail {
      color: var(--danger);
      background: rgba(220, 38, 38, 0.06);

      .conn-dot {
        background: var(--danger);
      }
    }

    .validate-id {
      margin-left: auto;
      font-size: 11px;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      color: var(--text-muted);
    }
  }

  .error-list {
    margin: 0;
    padding: 8px 14px 12px 34px;
    font-size: 12px;
    color: var(--danger);
    line-height: 1.8;
  }
}

// ===== 响应式 =====
@media (max-width: 768px) {
  .side-grid {
    grid-template-columns: 1fr;
  }

  .stepper-desc {
    display: none;
  }

  .conn-grid {
    grid-template-columns: 1fr 1fr;

    .conn-host {
      grid-column: 1 / -1;
    }
  }
}

// ===== Step3 比对策略 =====
.adv-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

// ===== 数据源快捷创建弹框 =====
.ds-form {
  padding-top: 4px;
}

.ds-row-top {
  display: grid;
  grid-template-columns: 1fr 200px;
  gap: 16px;
  align-items: start;
}

.ds-separator {
  height: 1px;
  margin: 4px 0 16px;
  background: var(--border);
}

.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.modal-footer-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.modal-footer-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
