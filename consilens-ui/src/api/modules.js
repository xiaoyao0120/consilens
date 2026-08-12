import request from "./index";

// ===== 任务 =====

export function listTasks(params) {
  return request.get("/tasks", { params });
}

export function getTask(taskId, config) {
  return request.get(`/tasks/${taskId}`, config || {});
}

export function getDiffReport(taskId, config) {
  return request.get(`/tasks/${taskId}/diff-report`, config || {});
}

export function submitTask(payload) {
  return request.post("/tasks/execute", payload);
}

export function retryTask(taskId) {
  return request.post(`/tasks/${taskId}/retry`);
}

export function cancelTask(taskId) {
  return request.post(`/tasks/${taskId}/cancel`);
}

// ===== 编排 =====

export function plan(payload) {
  return request.post("/plan", payload);
}

export function validate(payload) {
  return request.post("/validate", payload);
}

// ===== artifact =====

export function listArtifacts(params) {
  return request.get("/artifacts", { params });
}

export function getArtifact(artifactId) {
  return request.get(`/artifacts/${artifactId}`);
}

export function getArtifactContent(artifactId, config) {
  return request.get(`/artifacts/${artifactId}/content`, config || {});
}

// ===== 节点 =====

export function listNodes() {
  return request.get("/nodes");
}

export function getNode(nodeKey) {
  return request.get(`/nodes/${nodeKey}`);
}

// ===== 连接测试 =====

export function testConnection(payload, config) {
  return request.post("/connections/test", payload, config || {});
}

// ===== 数据源 =====

export function listDatasourceTypes() {
  return request.get("/datasources/types");
}

export function listDatasources() {
  return request.get("/datasources");
}

export function createDatasource(payload) {
  return request.post("/datasources", payload);
}

export function updateDatasource(id, payload) {
  return request.put(`/datasources/${id}`, payload);
}

export function deleteDatasource(id) {
  return request.delete(`/datasources/${id}`);
}

export function testDatasource(id) {
  return request.post(`/datasources/${id}/test`);
}

export function listDatasourceDatabases(id) {
  return request.get(`/datasources/${id}/databases`);
}

export function listDatasourceTables(id, database) {
  return request.get(`/datasources/${id}/databases/${encodeURIComponent(database)}/tables`);
}

export function listDatasourceColumns(id, database, table) {
  return request.get(
    `/datasources/${id}/databases/${encodeURIComponent(database)}/tables/${encodeURIComponent(table)}/columns`
  );
}

// ===== 任务定义 =====

export function listTaskDefinitions(params) {
  return request.get("/task-definitions", { params });
}

export function createTaskDefinition(payload) {
  return request.post("/task-definitions", payload);
}

export function getTaskDefinition(id) {
  return request.get(`/task-definitions/${id}`);
}

export function updateTaskDefinition(id, payload) {
  return request.put(`/task-definitions/${id}`, payload);
}

export function deleteTaskDefinition(id) {
  return request.delete(`/task-definitions/${id}`);
}

export function runTaskDefinition(id, payload) {
  return request.post(`/task-definitions/${id}/run`, payload || {});
}

export function toggleTaskDefinition(id, enabled) {
  return request.post(`/task-definitions/${id}/toggle`, null, { params: { enabled } });
}

// ===== 运行实例 =====

export function listTaskInstances(params) {
  return request.get("/task-instances", { params });
}

export function getTaskInstance(id) {
  return request.get(`/task-instances/${id}`);
}

export function submitTaskInstance(payload) {
  return request.post("/task-instances", payload);
}

// ===== 工作台 =====

export function getDashboardSummary() {
  return request.get("/dashboard/summary");
}
