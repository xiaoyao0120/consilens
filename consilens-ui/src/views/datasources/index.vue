<script setup>
const tableHeight = "calc(100vh - 250px)";
import { ref, reactive, computed, h, onMounted } from "vue";
import { useMessage, useDialog } from "naive-ui";
import { NButton, NIcon, NInput, NInputNumber, NSelect, NModal, NCard, NDrawer, NDrawerContent, NTree, NDataTable, NTag, NSpin, NPopconfirm, NEmpty } from "naive-ui";
import { AddOutline, LinkOutline, CubeOutline, GridOutline, ListOutline, ServerOutline, EyeOutline } from "@vicons/ionicons5";
import {
  listDatasourceTypes,
  listDatasources,
  createDatasource,
  updateDatasource,
  deleteDatasource,
  testDatasource,
  listDatasourceDatabases,
  listDatasourceTables,
  listDatasourceColumns,
} from "@/api/modules";
import { formatTime } from "@/utils/format";

const message = useMessage();
const dialog = useDialog();

// ===== 列表 =====
const loading = ref(false);
const items = ref([]);
const types = ref([]);

async function loadTypes() {
  try {
    types.value = await listDatasourceTypes();
  } catch {
    types.value = [];
  }
}

async function loadData() {
  loading.value = true;
  try {
    items.value = (await listDatasources()) || [];
  } finally {
    loading.value = false;
  }
}

// ===== 新建 / 编辑 =====
const modal = reactive({
  show: false,
  editing: false,
  id: null,
  saving: false,
  testing: false,
  testResult: null,
  form: {
    name: "",
    type: "",
    host: "",
    port: null,
    database: "",
    username: "",
    password: "",
  },
});

function openCreate() {
  modal.editing = false;
  modal.id = null;
  modal.testResult = null;
  const defaultType = types.value.find((item) => item.type === "mysql")?.type
    || types.value[0]?.type
    || "mysql";
  modal.form = { name: "", type: defaultType, host: "", port: null, database: "", username: "", password: "" };
  modal.show = true;
}

function openEdit(row) {
  modal.editing = true;
  modal.id = row.id;
  modal.testResult = null;
  const param = row.param || {};
  modal.form = {
    name: row.name,
    type: row.type,
    host: param.host || "",
    port: param.port != null ? Number(param.port) : null,
    database: param.database || "",
    username: param.username || "",
    password: "", // 密码不出接口，留空保存时保留原密码
  };
  modal.show = true;
}

function buildParam() {
  const param = {
    host: modal.form.host.trim(),
    database: modal.form.database.trim() || undefined,
    username: modal.form.username.trim() || undefined,
  };
  if (modal.form.port) param.port = modal.form.port;
  if (modal.form.password) param.password = modal.form.password;
  return param;
}

async function handleSave() {
  if (!modal.form.name.trim() || !modal.form.host.trim()) {
    message.warning("请填写名称与主机地址");
    return;
  }
  modal.saving = true;
  try {
    const payload = { name: modal.form.name.trim(), type: modal.form.type, param: buildParam() };
    if (modal.editing) {
      await updateDatasource(modal.id, payload);
      message.success("数据源已更新");
    } else {
      await createDatasource(payload);
      message.success("数据源已创建");
    }
    modal.show = false;
    loadData();
  } catch (error) {
    message.error(error?.response?.data?.error || "保存失败");
  } finally {
    modal.saving = false;
  }
}

async function handleModalTest() {
  if (!modal.form.host.trim()) {
    message.warning("请先填写主机地址");
    return;
  }
  modal.testing = true;
  modal.testResult = null;
  try {
    const result = await testConnectionLocal();
    modal.testResult = { ok: result.success, text: result.error || "连接成功" };
  } catch (error) {
    modal.testResult = { ok: false, text: error?.response?.data?.error || "连接失败" };
  } finally {
    modal.testing = false;
  }
}

async function testConnectionLocal() {
  // 弹窗内测试：优先走已保存的数据源（编辑态），新建态走 /v1/connections/test
  if (modal.editing && modal.id) {
    return testDatasource(modal.id);
  }
  const { testConnection } = await import("@/api/modules");
  return testConnection({
    type: modal.form.type,
    host: modal.form.host.trim(),
    port: modal.form.port || undefined,
    database: modal.form.database.trim() || undefined,
    username: modal.form.username.trim() || undefined,
    password: modal.form.password || undefined,
  });
}

async function handleRowTest(row) {
  try {
    const result = await testDatasource(row.id);
    if (result.success) {
      message.success("连接成功");
    } else {
      message.error(result.error || "连接失败");
    }
  } catch (error) {
    message.error(error?.response?.data?.error || "连接失败");
  }
}

function handleDelete(row) {
  dialog.warning({
    title: "删除数据源",
    content: `确认删除数据源「${row.name}」吗？该操作不可恢复。`,
    positiveText: "删除",
    negativeText: "取消",
    onPositiveClick: async () => {
      try {
        await deleteDatasource(row.id);
        message.success("已删除");
        loadData();
      } catch (error) {
        message.error(error?.response?.data?.error || "删除失败");
      }
    },
  });
}

// ===== 浏览（元数据树）=====
const browse = reactive({
  show: false,
  loading: false,
  current: null,
  data: [],
  columns: [],
  selectedTable: "",
});

async function openBrowse(row) {
  browse.current = row;
  browse.data = [];
  browse.columns = [];
  browse.show = true;
  browse.loading = true;
  try {
    const databases = await listDatasourceDatabases(row.id);
    browse.data = databases.map((name) => ({
      key: `db::${name}`,
      label: name,
      isLeaf: false,
      database: name,
      // 不提供 children：懒加载（展开时触发 on-load 获取表）
    }));
  } catch (error) {
    message.error(error?.response?.data?.error || "获取数据库列表失败");
    browse.data = [];
  } finally {
    browse.loading = false;
  }
}

async function loadTreeNode(node) {
  if (node.isLeaf) return;
  const id = browse.current.id;
  if (node.database && !node.table) {
    const tables = await listDatasourceTables(id, node.database);
    node.children = tables.map((name) => ({
      key: `tbl::${node.database}::${name}`,
      label: name,
      isLeaf: false,
      database: node.database,
      table: name,
    }));
  } else if (node.table) {
    const columns = await listDatasourceColumns(id, node.database, node.table);
    node.children = columns.map((column) => ({
      key: `col::${node.database}::${node.table}::${column.name}`,
      label: `${column.name}  ${column.dataType || ""}`.trim(),
      isLeaf: true,
    }));
  }
}

// 点击（选中）表节点时，在右侧加载列信息
async function handleTreeSelect(keys) {
  const key = Array.isArray(keys) ? keys[0] : null;
  if (!key || !key.startsWith("tbl::")) {
    return;
  }
  const [, database, table] = key.split("::");
  if (!database || !table || !browse.current) return;
  browse.selectedTable = table;
  browse.columns = [];
  try {
    browse.columns = await listDatasourceColumns(browse.current.id, database, table);
  } catch (error) {
    message.error(error?.response?.data?.error || "获取列信息失败");
  }
}

// 树节点图标：库 / 表 / 列
function renderTreeLabel({ option }) {
  let icon = ListOutline;
  if (option.key?.startsWith("db::")) icon = CubeOutline;
  else if (option.key?.startsWith("tbl::")) icon = GridOutline;
  return h("span", { class: "tree-label" }, [
    h(NIcon, { size: 15, class: "tree-icon" }, { default: () => h(icon) }),
    h("span", { class: "tree-text" }, option.label),
  ]);
}

const columnColumns = [
  { title: "列名", key: "name", minWidth: 140, render: (row) => h("span", { class: "mono" }, row.name) },
  { title: "类型", key: "dataType", width: 120, render: (row) => h("span", { class: "text-secondary" }, row.dataType || "-") },
  {
    title: "可空",
    key: "nullable",
    width: 80,
    render: (row) =>
      row.nullable === null || row.nullable === undefined
        ? h("span", { class: "text-muted" }, "-")
        : h(NTag, { size: "small", type: row.nullable ? "default" : "info" }, row.nullable ? "可空" : "非空"),
  },
];

const columns = [
  {
    title: "名称",
    key: "name",
    minWidth: 200,
    render: (row) => h("div", { class: "ds-name" }, [
      h(NIcon, { size: 15, class: "ds-icon" }, { default: () => h(LinkOutline) }),
      h("span", row.name),
    ]),
  },
  {
    title: "类型",
    key: "type",
    width: 120,
    render: (row) => h(NTag, { size: "small", bordered: false }, row.type),
  },
  {
    title: "创建时间",
    key: "createdAt",
    width: 170,
    render: (row) => h("span", { class: "text-secondary" }, formatTime(row.createdAt)),
  },
  {
    title: "操作",
    key: "actions",
    width: 240,
    render: (row) =>
      h("div", { class: "row-actions" }, [
        h(
          NButton,
          { size: "small", text: true, type: "primary", onClick: () => handleRowTest(row) },
          { default: () => "测试连接" }
        ),
        h(
          NButton,
          { size: "small", text: true, type: "primary", onClick: () => openBrowse(row) },
          { default: () => "浏览" }
        ),
        h(
          NButton,
          { size: "small", text: true, onClick: () => openEdit(row) },
          { default: () => "编辑" }
        ),
        h(
          NButton,
          { size: "small", text: true, type: "error", onClick: () => handleDelete(row) },
          { default: () => "删除" }
        ),
      ]),
  },
];

const typeOptions = computed(() => types.value.map((item) => ({
  label: `${item.type}（默认端口 ${item.defaultPort}）`,
  value: item.type,
})));

onMounted(() => {
  loadTypes();
  loadData();
});
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">数据源</div>
        <div class="page-desc">管理数据连接，供任务创建时直接选用，支持实时浏览库 / 表 / 列</div>
      </div>
      <n-button type="primary" @click="openCreate">
        <template #icon>
          <n-icon><AddOutline /></n-icon>
        </template>
        新建数据源
      </n-button>
    </div>

    <n-card :bordered="true">
      <template #header>
        <div class="flex-bc">
          <span class="card-title">数据源列表</span>
          <span class="text-muted">共 {{ items.length }} 个</span>
        </div>
      </template>
      <n-data-table
        :columns="columns"
        :data="items"
        :loading="loading"
        :row-key="(row) => row.id"
        :max-height="tableHeight"
        :bordered="false"
        size="small"
      />
    </n-card>

    <!-- 新建 / 编辑弹窗 -->
    <n-modal v-model:show="modal.show" preset="card" style="width: 560px" :title="modal.editing ? '编辑数据源' : '新建数据源'">
      <div class="ds-form">
        <div class="field">
          <label class="field-label">名称</label>
          <n-input v-model:value="modal.form.name" placeholder="例如：生产订单库" />
        </div>
        <div class="field">
          <label class="field-label">类型</label>
          <n-select v-model:value="modal.form.type" :options="typeOptions" />
        </div>
        <div class="field">
          <label class="field-label">主机</label>
          <div class="inline-row">
            <n-input v-model:value="modal.form.host" placeholder="IP 或主机名" class="flex-1" />
            <n-input-number v-model:value="modal.form.port" placeholder="端口" style="width: 120px" :min="1" :max="65535" />
          </div>
        </div>
        <div class="field">
          <label class="field-label">数据库</label>
          <n-input v-model:value="modal.form.database" placeholder="数据库 / schema / service name" />
        </div>
        <div class="field">
          <label class="field-label">用户名</label>
          <n-input v-model:value="modal.form.username" />
        </div>
        <div class="field">
          <label class="field-label">密码</label>
          <n-input
            v-model:value="modal.form.password"
            type="password"
            show-password-on="click"
            :placeholder="modal.editing ? '留空则保持原密码' : ''"
          />
        </div>

        <div class="test-row">
          <n-button class="btn-ghost btn-sm" :loading="modal.testing" @click="handleModalTest">测试连接</n-button>
          <span v-if="modal.testResult" class="conn-result" :class="modal.testResult.ok ? 'ok' : 'fail'">
            <span class="conn-dot" />{{ modal.testResult.text }}
          </span>
        </div>
      </div>
      <template #footer>
        <div class="flex-bc">
          <div />
          <div class="flex-ac" style="gap: 8px">
            <n-button class="btn-ghost" @click="modal.show = false">取消</n-button>
            <n-button type="primary" :loading="modal.saving" @click="handleSave">
              {{ modal.editing ? "保存" : "创建" }}
            </n-button>
          </div>
        </div>
      </template>
    </n-modal>

    <!-- 浏览抽屉 -->
    <n-drawer v-model:show="browse.show" :width="780">
      <n-drawer-content :title="`浏览数据源：${browse.current?.name || ''}`" closable>
        <div class="browse-grid">
          <div class="browse-panel">
            <div class="browse-title">
              <n-icon size="15"><ServerOutline /></n-icon>
              <span>库 / 表</span>
              <span class="browse-count">{{ browse.data.length }}</span>
            </div>
            <n-spin :show="browse.loading">
              <n-tree
                v-if="browse.data.length"
                :data="browse.data"
                block-line
                :render-label="renderTreeLabel"
                :on-load="loadTreeNode"
                @update:selected-keys="handleTreeSelect"
              />
              <n-empty v-else-if="!browse.loading" description="未获取到数据库列表" size="small" />
            </n-spin>
          </div>
          <div class="browse-panel">
            <div class="browse-title">
              <n-icon size="15"><ListOutline /></n-icon>
              <span>列信息</span>
              <span v-if="browse.selectedTable" class="browse-table-tag">{{ browse.selectedTable }}</span>
            </div>
            <n-data-table
              :columns="columnColumns"
              :data="browse.columns"
              size="small"
              :bordered="false"
              :max-height="480"
            />
            <div v-if="!browse.columns.length" class="browse-hint">
              在左侧选择一张表以查看其列定义
            </div>
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped lang="scss">
// ===== 按钮 =====
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

.ds-form {
  padding-top: 4px;
}

.field {
  margin-bottom: 16px;
}

.field-label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

.inline-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.test-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.conn-result {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  word-break: break-all;

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

.ds-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
  color: var(--text);
}

.ds-icon {
  color: var(--text-muted);
}

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.browse-grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 14px;
  height: 100%;
  align-items: stretch;
}

.browse-panel {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  min-height: 480px;
}

.browse-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border);
}

.browse-count {
  margin-left: auto;
  padding: 0 8px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-secondary);
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 999px;
}

.browse-table-tag {
  margin-left: auto;
  max-width: 60%;
  padding: 1px 10px;
  font-size: 11px;
  font-weight: 500;
  color: var(--primary);
  background: rgba(37, 99, 235, 0.08);
  border-radius: 999px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.tree-icon {
  color: var(--text-muted);
  flex-shrink: 0;
}

.tree-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.browse-hint {
  margin-top: 24px;
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
