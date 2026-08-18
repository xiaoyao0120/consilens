import request from "./index";

// ===== AI Agent 会话（契约见 docs/06-AI-Agent连续对话设计.md 第 27 节）=====

// 客户端幂等请求 ID：优先使用 crypto.randomUUID，低版本浏览器降级
export function newRequestId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `req_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}

// 创建会话：201 -> { session: { id, status, lastSeq, workingState } }
export function createAiSession(payload) {
  return request.post("/ai/sessions", payload);
}

// 会话详情：返回状态、workingState、pending action、lastSeq
export function getAiSession(sessionId) {
  return request.get(`/ai/sessions/${sessionId}`);
}

// 删除会话（级联删除事件/运行/审批/凭据/计划）
export function deleteAiSession(sessionId) {
  return request.delete(`/ai/sessions/${sessionId}`);
}

// 发送消息：202 -> { runId }
export function sendAiMessage(sessionId, payload) {
  return request.post(`/ai/sessions/${sessionId}/messages`, payload);
}

// JSON 分页事件（SSE 不可用 / 跳号补齐时的恢复接口）
export function fetchAiEvents(sessionId, params) {
  return request.get(`/ai/sessions/${sessionId}/events`, { params });
}

// 安全表单提交秘密：独立 DTO，不进入对话正文
export function submitAiSecrets(sessionId, payload) {
  return request.post(`/ai/sessions/${sessionId}/secrets`, payload);
}

// 审批决策：{ requestId, decision, actionDigest, version }
export function decideAiApproval(sessionId, approvalId, payload) {
  return request.post(`/ai/sessions/${sessionId}/approvals/${approvalId}`, payload);
}

// 取消 / 重试（幂等；retry 需要 requestId 幂等键）
export function cancelAiRun(sessionId) {
  return request.post(`/ai/sessions/${sessionId}/cancel`);
}

export function retryAiRun(sessionId, payload) {
  return request.post(`/ai/sessions/${sessionId}/retry`, payload);
}
