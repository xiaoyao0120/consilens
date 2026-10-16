<script setup>
import { ref, reactive, computed, watch, nextTick } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useMessage } from "naive-ui";
import { NButton, NIcon, NInput, NInputNumber, NSelect, NDynamicTags, NSwitch, NSpin, NCheckbox, NRadioGroup, NRadioButton, NModal } from "naive-ui";
import { ArrowBackOutline, ArrowForwardOutline, CheckmarkCircleOutline, GitCompareOutline, RefreshOutline, SaveOutline } from "@vicons/ionicons5";
import { createTaskDefinition, updateTaskDefinition, getTaskDefinition, runTaskDefinition, listDatasources, listDatasourceDatabases, listDatasourceTables, listDatasourceColumns, listDatasourceTypes, getDatasourceTypeConfig, createDatasource, testConnection } from "@/api/modules";
import { generateSerialNo } from "@/utils/format";
import DataSourceForm from "@/components/DataSourceForm.vue";
import CodeBlock from "@/components/common/CodeBlock.vue";
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

// 表单默认值工厂：声明与「清空草稿」共用，避免两处默认值漂移
function defaultPlanForm() {
  return {
    goal: "生成源表与目标表的数据对比配置",
    source: makeSide(),
    target: makeSide(),
    keys: [],
  };
}

function defaultAdvForm() {
  return {
    mode: "checksum",          // strategy.mode: checksum | join
    algorithm: "concat",       // strategy.algorithm / checksumAlgorithm
    bisectionFactor: 4,        // strategy.bisectionFactor
    bisectionThreshold: 5000,  // strategy.bisectionThreshold
    batchSize: 1000,           // strategy.batchSize（透传 attributes）
    localCompareMode: "full",  // strategy.localCompare.mode: full | auto
    validateUniqueKeys: true,  // 校验唯一键
    maxDifferences: null,      // 最大差异数（可选）
    enableProfiling: false,    // 性能分析
  };
}

function defaultPlatformForm() {
  return {
    platform: "local",
    yarn: {
      archive: "",
      queue: "",
      amMemory: "1g",
      amVCores: 1,
      stagingDir: "",
      files: "",
      jars: "",
      tags: "",
      maxAppAttempts: null,
    },
    kubernetes: {
      image: "",
      namespace: "default",
      jobName: "",
      memory: "1g",
      cpu: "0.5",
      serviceAccount: "",
      imagePullSecrets: "",
      envsText: "",
      secretEnvText: "",
    },
  };
}

const planForm = reactive(defaultPlanForm());
const compareForm = reactive({
  keyMappings: [], // [{source, target}] 主键映射
  fieldMappings: [], // [{source, target}] 比较字段映射
  ignoreColumns: [], // [name] 排除字段
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
const platformForm = reactive(defaultPlatformForm());

const runForm = reactive({
  serialNo: generateSerialNo(),
  timeoutMs: 300000,
  dryRun: false,
});

function regenerateSerialNo() {
  runForm.serialNo = generateSerialNo();
}

// ===== 比对策略高级选项（对齐 CLI strategy 配置）=====
const advForm = reactive(defaultAdvForm());

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

// ===== 草稿持久化：非编辑模式下自动保存/恢复，误关页面不丢填写内容 =====
const DRAFT_KEY = "consilens-wizard-draft";
const DRAFT_VERSION = 1;
const draftRestored = ref(false);

function writeDraft() {
  if (editId.value) return;
  try {
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        version: DRAFT_VERSION,
        currentStep: currentStep.value,
        planForm,
        compareForm,
        advForm,
        platformForm,
        runForm,
        defForm,
      })
    );
  } catch {
    // 存储不可用（隐私模式等）时静默
  }
}

let draftTimer = null;
// 清空草稿后的抑制窗口：resetForms 触发的 watch 不再把默认表单写回存储
let draftSuspended = false;
watch(
  [planForm, compareForm, advForm, platformForm, runForm, defForm, currentStep],
  () => {
    if (editId.value || draftSuspended) return;
    clearTimeout(draftTimer);
    draftTimer = setTimeout(writeDraft, 400);
  },
  { deep: true }
);

function restoreDraft() {
  if (editId.value) return false;
  try {
    const raw = localStorage.getItem(DRAFT_KEY);
    if (!raw) return false;
    const draft = JSON.parse(raw);
    if (draft.version !== DRAFT_VERSION) return false;
    Object.assign(planForm, draft.planForm || {});
    Object.assign(compareForm, draft.compareForm || {});
    Object.assign(advForm, draft.advForm || {});
    Object.assign(platformForm, draft.platformForm || {});
    Object.assign(runForm, draft.runForm || {});
    Object.assign(defForm, draft.defForm || {});
    currentStep.value = draft.currentStep || 1;
    return true;
  } catch {
    return false;
  }
}

function resetForms() {
  Object.assign(planForm, defaultPlanForm());
  Object.assign(compareForm, { keyMappings: [], fieldMappings: [], ignoreColumns: [] });
  Object.assign(advForm, defaultAdvForm());
  Object.assign(platformForm, defaultPlatformForm());
  Object.assign(runForm, { serialNo: generateSerialNo(), timeoutMs: 300000, dryRun: false });
  Object.assign(defForm, { name: "", description: "", runNow: true });
  currentStep.value = 1;
}

function clearDraft() {
  draftSuspended = true;
  localStorage.removeItem(DRAFT_KEY);
  resetForms();
  draftRestored.value = false;
  message.success("草稿已清空");
  setTimeout(() => {
    draftSuspended = false;
  }, 600);
}

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
    platformForm.platform = config.platform || "local";
    const savedProperties = config.properties || config.cluster?.[platformForm.platform] || {};
    restorePlatformProperties(savedProperties);
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

function platformProperties() {
  if (platformForm.platform === "yarn") {
    if (!platformForm.yarn.archive.trim()) {
      throw new Error("请填写 YARN Runtime Archive");
    }
    return omitEmpty({ ...platformForm.yarn });
  }
  if (!platformForm.kubernetes.image.trim()) {
    throw new Error("请填写 Kubernetes 镜像");
  }
  return omitEmpty({
    image: platformForm.kubernetes.image,
    namespace: platformForm.kubernetes.namespace,
    jobName: platformForm.kubernetes.jobName,
    memory: platformForm.kubernetes.memory,
    cpu: platformForm.kubernetes.cpu,
    serviceAccount: platformForm.kubernetes.serviceAccount,
    imagePullSecrets: platformForm.kubernetes.imagePullSecrets,
    envs: parseKeyValueLines(platformForm.kubernetes.envsText, "环境变量"),
    secretEnv: parseSecretEnvLines(platformForm.kubernetes.secretEnvText),
  });
}

function omitEmpty(values) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== null
    && value !== undefined && String(value).trim() !== ""));
}

function parseKeyValueLines(text, label) {
  if (!text.trim()) return undefined;
  const result = {};
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const separator = trimmed.indexOf("=");
    if (separator <= 0) {
      throw new Error(`${label}格式应为 KEY=VALUE`);
    }
    result[trimmed.slice(0, separator).trim()] = trimmed.slice(separator + 1).trim();
  }
  return result;
}

function parseSecretEnvLines(text) {
  const references = parseKeyValueLines(text, "Secret 环境变量");
  if (!references) return undefined;
  const result = {};
  Object.entries(references).forEach(([name, value]) => {
    const separator = value.indexOf("/");
    if (separator <= 0 || separator === value.length - 1) {
      throw new Error("Secret 环境变量格式应为 ENV_NAME=secretName/secretKey");
    }
    result[name] = {
      secretName: value.slice(0, separator).trim(),
      secretKey: value.slice(separator + 1).trim(),
    };
  });
  return result;
}

function restorePlatformProperties(properties) {
  const yarn = properties || {};
  if (platformForm.platform === "yarn") {
    Object.assign(platformForm.yarn, {
      archive: yarn.archive || "",
      queue: yarn.queue || "",
      amMemory: yarn.amMemory || "1g",
      amVCores: yarn.amVCores ?? 1,
      stagingDir: yarn.stagingDir || "",
      files: yarn.files || "",
      jars: yarn.jars || "",
      tags: yarn.tags || "",
      maxAppAttempts: yarn.maxAppAttempts ?? null,
    });
    return;
  }
  const kubernetes = properties || {};
  Object.assign(platformForm.kubernetes, {
    image: kubernetes.image || "",
    namespace: kubernetes.namespace || "default",
    jobName: kubernetes.jobName || "",
    memory: kubernetes.memory || "1g",
    cpu: kubernetes.cpu || "0.5",
    serviceAccount: kubernetes.serviceAccount || "",
    imagePullSecrets: kubernetes.imagePullSecrets || "",
    envsText: Object.entries(kubernetes.envs || {}).map(([name, value]) => `${name}=${value}`).join("\n"),
    secretEnvText: Object.entries(kubernetes.secretEnv || {})
      .map(([name, value]) => `${name}=${value.secretName}/${value.secretKey}`).join("\n"),
  });
}

const configDisplay = computed(() => {
  try {
    return JSON.stringify(buildConfig(), null, 2);
  } catch (error) {
    return `配置错误：${error.message}`;
  }
});

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
    const config = buildConfig();
    const payload = {
      name: defForm.name.trim(),
      description: defForm.description.trim() || undefined,
      config,
    };
    const definition = editId.value
      ? await updateTaskDefinition(editId.value, payload)
      : await createTaskDefinition(payload);
    if (defForm.runNow) {
      const accepted = await runTaskDefinition(definition.id);
      localStorage.removeItem(DRAFT_KEY);
      message.success(`${editId.value ? "已更新定义" : "已保存定义"}并提交运行：${accepted.taskId}`);
      router.push(`/instances/${accepted.taskId}`);
    } else {
      localStorage.removeItem(DRAFT_KEY);
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

// 进入页面时预加载数据源列表；编辑模式加载定义；新建模式恢复草稿
if (editId.value) {
  loadForEdit();
} else {
  draftRestored.value = restoreDraft();
  if (draftRestored.value) {
    message.success("已恢复上次未提交的草稿");
  }
}
loadDatasources();
</script>

<template>
  <div class="page-container">
    <div class="wizard-wrap">
      <!-- 页头 -->
      <div class="wizard-head">
        <div class="wizard-head-row">
          <a class="back-link" @click="router.push('/definitions')">
            <n-icon size="14"><ArrowBackOutline /></n-icon>
            返回定义列表
          </a>
          <a v-if="draftRestored && !editId" class="draft-clear" @click="clearDraft">清空草稿</a>
        </div>
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
                <n-button size="small" @click="openDsModal(side)" title="新建数据源">+ 添加</n-button>
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
              <n-button size="small" quaternary type="error" @click="removeMapping('keyMappings', index)">移除</n-button>
            </div>
            <div class="section-actions">
              <n-button size="small" @click="autoMatchKeys">自动匹配同名字段</n-button>
              <n-button size="small" @click="addMapping('keyMappings')">+ 添加主键</n-button>
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
              <n-button size="small" quaternary type="error" @click="removeMapping('fieldMappings', index)">移除</n-button>
            </div>
            <div class="section-actions">
              <n-button size="small" @click="autoMatchFields">自动匹配同名字段</n-button>
              <n-button size="small" @click="addMapping('fieldMappings')">+ 添加映射</n-button>
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
            <n-input v-model:value="runForm.serialNo" class="flex-1" size="small" />
            <n-button size="small" @click="regenerateSerialNo">重新生成</n-button>
          </div>
        </div>
        <div class="field">
          <label class="field-label">超时时间</label>
          <div class="inline-row">
            <n-input-number v-model:value="runForm.timeoutMs" :min="1000" :step="1000" class="flex-1" />
            <span class="unit-hint">毫秒</span>
          </div>
        </div>
        <div class="field">
          <label class="field-label">执行平台</label>
          <n-radio-group v-model:value="platformForm.platform" size="small">
            <n-radio-button value="local">Local（Server 内）</n-radio-button>
            <n-radio-button value="yarn">YARN</n-radio-button>
            <n-radio-button value="kubernetes">Kubernetes</n-radio-button>
          </n-radio-group>
        </div>
        <template v-if="platformForm.platform === 'yarn'">
          <div class="field">
            <label class="field-label">Runtime Archive（HDFS）<span class="required">*</span></label>
            <n-input v-model:value="platformForm.yarn.archive" placeholder="hdfs:///apps/consilens/runtime.zip" />
          </div>
          <div class="adv-grid">
            <div class="field">
              <label class="field-label">队列</label>
              <n-input v-model:value="platformForm.yarn.queue" placeholder="default" />
            </div>
            <div class="field">
              <label class="field-label">AM 内存</label>
              <n-input v-model:value="platformForm.yarn.amMemory" placeholder="1g" />
            </div>
            <div class="field">
              <label class="field-label">AM vCores</label>
              <n-input-number v-model:value="platformForm.yarn.amVCores" :min="1" :precision="0" class="flex-1" />
            </div>
            <div class="field">
              <label class="field-label">最大重试次数</label>
              <n-input-number v-model:value="platformForm.yarn.maxAppAttempts" :min="1" :precision="0" class="flex-1" placeholder="默认 1" />
            </div>
          </div>
          <div class="field">
            <label class="field-label">Staging 目录</label>
            <n-input v-model:value="platformForm.yarn.stagingDir" placeholder="hdfs:///user/consilens/.staging" />
          </div>
          <div class="field">
            <label class="field-label">附加文件</label>
            <n-input v-model:value="platformForm.yarn.files" placeholder="多个文件以英文逗号分隔" />
          </div>
          <div class="field">
            <label class="field-label">附加 JAR</label>
            <n-input v-model:value="platformForm.yarn.jars" placeholder="多个 JAR 以英文逗号分隔" />
          </div>
          <div class="field">
            <label class="field-label">应用标签</label>
            <n-input v-model:value="platformForm.yarn.tags" placeholder="多个标签以英文逗号分隔" />
          </div>
        </template>
        <template v-if="platformForm.platform === 'kubernetes'">
          <div class="field">
            <label class="field-label">运行镜像<span class="required">*</span></label>
            <n-input v-model:value="platformForm.kubernetes.image" placeholder="registry.example/consilens-runtime:0.3.0" />
          </div>
          <div class="adv-grid">
            <div class="field">
              <label class="field-label">Namespace</label>
              <n-input v-model:value="platformForm.kubernetes.namespace" />
            </div>
            <div class="field">
              <label class="field-label">Job 名称</label>
              <n-input v-model:value="platformForm.kubernetes.jobName" placeholder="留空自动生成" />
            </div>
            <div class="field">
              <label class="field-label">内存</label>
              <n-input v-model:value="platformForm.kubernetes.memory" placeholder="1g" />
            </div>
            <div class="field">
              <label class="field-label">CPU 核数</label>
              <n-input v-model:value="platformForm.kubernetes.cpu" placeholder="0.5" />
            </div>
          </div>
          <div class="field">
            <label class="field-label">Service Account</label>
            <n-input v-model:value="platformForm.kubernetes.serviceAccount" placeholder="可选" />
          </div>
          <div class="field">
            <label class="field-label">镜像拉取 Secret</label>
            <n-input v-model:value="platformForm.kubernetes.imagePullSecrets" placeholder="多个 Secret 以英文逗号分隔" />
          </div>
          <div class="field">
            <label class="field-label">环境变量</label>
            <n-input v-model:value="platformForm.kubernetes.envsText" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="每行一个，例如：TZ=Asia/Shanghai" />
          </div>
          <div class="field">
            <label class="field-label">数据库密码 Secret</label>
            <n-input v-model:value="platformForm.kubernetes.secretEnvText" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="SOURCE_PASSWORD=compare-db/source-password&#10;TARGET_PASSWORD=compare-db/target-password" />
            <div class="field-desc">存在数据库密码时必须填写 SOURCE_PASSWORD 与 TARGET_PASSWORD；每行格式为 ENV_NAME=secretName/secretKey。</div>
          </div>
        </template>
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
        <CodeBlock :content="configDisplay" label="运行配置（保存到定义）" :max-height="320" />
      </div>

      <!-- 底部操作栏（固定，卡片内部滚动） -->
      <div class="step-bar">
        <div class="step-bar-inner">
          <n-button v-if="currentStep > 1" size="small" @click="goPrev">
            <template #icon>
              <n-icon><ArrowBackOutline /></n-icon>
            </template>
            上一步
          </n-button>
          <div class="flex-1" />
          <template v-if="currentStep < 4">
            <n-button type="primary" size="small" @click="goNext">
              下一步
              <template #icon>
                <n-icon><ArrowForwardOutline /></n-icon>
              </template>
            </n-button>
          </template>
          <template v-else>
            <n-button type="primary" size="small" :loading="submitting" @click="handleSaveDefinition">
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
              <n-button size="small" :loading="dsModal.testing" @click="handleDsTest">测试连接</n-button>
              <span v-if="dsModal.testResult" class="conn-result" :class="dsModal.testResult.ok ? 'ok' : 'fail'">
                <span class="conn-dot" />{{ dsModal.testResult.text }}
              </span>
            </div>
            <div class="modal-footer-right">
              <n-button size="small" @click="dsModal.show = false">取消</n-button>
              <n-button size="small" type="primary" :loading="dsModal.saving" @click="handleDsCreate">创建</n-button>
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
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.wizard-head {
  flex-shrink: 0;

  .wizard-head-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .back-link {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12.5px;
    color: var(--text-muted);
    cursor: pointer;
    text-decoration: none;

    &:hover {
      color: var(--text-secondary);
    }
  }

  .draft-clear {
    font-size: 12px;
    color: var(--text-muted);
    cursor: pointer;

    &:hover {
      color: var(--danger);
    }
  }

  .wizard-title {
    margin: 8px 0 2px;
    font-size: 16px;
    font-weight: 600;
    line-height: 24px;
    color: var(--text);
  }

  .wizard-subtitle {
    margin: 0;
    font-size: 12.5px;
    color: var(--text-muted);
  }
}

// ===== 步骤条 =====
.stepper {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  padding: 16px 20px;
  margin-top: 12px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.stepper-item {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  cursor: default;
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
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: 50%;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-muted);
  background: var(--bg-subtle);
  border: 1px solid var(--border);

  .stepper-item.active & {
    color: #fff;
    background: var(--primary);
    border-color: var(--primary);
  }

  .stepper-item.done & {
    color: var(--primary);
    border-color: var(--primary);
    background: var(--card);
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
  border-radius: var(--radius-card);
  padding: 20px;
  margin-top: 12px;
}

.panel-head {
  padding-bottom: 14px;
  margin-bottom: 18px;
  border-bottom: 1px solid var(--border);

  .panel-title {
    margin: 0;
    font-size: 13.5px;
    font-weight: 600;
    color: var(--text);
  }

  .panel-desc {
    margin: 2px 0 0;
    font-size: 12px;
    color: var(--text-muted);
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
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
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
  border-radius: var(--radius-ctl);
  background: var(--bg-subtle);
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
  gap: 12px;
  margin-bottom: 4px;
}

.side-card {
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-ctl);
  background: var(--bg-subtle);
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
    min-width: 22px;
    height: 22px;
    padding: 0 6px;
    border-radius: var(--radius-badge);
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

// ===== Step 2: 比对键 =====
.section {
  margin-bottom: 22px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-ctl);
  background: var(--bg-subtle);
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
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-ctl);
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

.hint-box {
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-ctl);
  background: var(--bg-subtle);
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

// ===== 确认页 =====
.summary-list {
  margin-bottom: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-ctl);
  overflow: hidden;
}

.summary-row {
  display: flex;
  align-items: baseline;
  padding: 10px 16px;
  font-size: 13px;

  &:nth-child(odd) {
    background: var(--bg-subtle);
  }

  .summary-label {
    flex: 0 0 90px;
    color: var(--text-muted);
  }

  .summary-value {
    flex: 1;
    color: var(--text);
    word-break: break-all;
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
