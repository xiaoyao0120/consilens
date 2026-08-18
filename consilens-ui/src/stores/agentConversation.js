import { defineStore } from "pinia";
import {
  createAiSession,
  getAiSession,
  sendAiMessage,
  deleteAiSession,
  fetchAiEvents,
  decideAiApproval,
  cancelAiRun,
  retryAiRun,
  newRequestId,
} from "@/api/agent";

// 本地会话索引（仅存非敏感元数据：id/title/更新时间/最近状态；不存事件、不存 secret）
const SESSION_INDEX_KEY = "consilens-ai-session-index";

// ===== 纯函数（便于单元测试 / 静态自查）=====

// 事件 seq 规范化：非数值返回 null（调用方按非法事件忽略）
export function toSeq(event) {
  if (!event || event.seq === undefined || event.seq === null) return null;
  const seq = Number(event.seq);
  return Number.isFinite(seq) ? seq : null;
}

// 已应用过（seq <= lastSeq）的事件视为重复，直接丢弃
export function isDuplicateEvent(seq, lastSeq) {
  return seq <= lastSeq;
}

// 跳号数量：0 表示连续，>0 表示中间缺失
export function gapSize(seq, lastSeq) {
  return seq - lastSeq - 1;
}

// 断线退避：1/2/4/8… 秒，最大 30 秒
export function nextBackoffMs(attempt) {
  const ms = 1000 * 2 ** attempt;
  return Math.min(ms, 30000);
}

// SSE 无数据看门狗：后端 15s heartbeat，40s 无任何字节判定假死（落在 30-45s 区间）
export const SSE_WATCHDOG_MS = 40000;

export function watchdogExpired(lastDataAt, now = Date.now(), thresholdMs = SSE_WATCHDOG_MS) {
  return now - lastDataAt > thresholdMs;
}

// 兼容后端 JSON 分页事件的不同返回结构：
// 直接数组 / {events:[...],lastSeq} / {data:{events:[...]}} / items / records / list
export function extractEventList(res) {
  if (Array.isArray(res)) return res;
  if (!res || typeof res !== "object") return [];
  const candidates = [res.items, res.records, res.events, res.list];
  for (const list of candidates) {
    if (Array.isArray(list)) return list;
  }
  if (res.data && typeof res.data === "object") {
    for (const list of [res.data.events, res.data.items, res.data.records]) {
      if (Array.isArray(list)) return list;
    }
  }
  return [];
}

// secret 字段归一：兼容字符串数组（["password"]）与对象数组（[{field,title,type,placeholder,required,rows}]）
export function normalizeSecretFields(fields) {
  return (fields || []).map((field) => {
    if (typeof field === "string") {
      return { field, title: field, type: "password", placeholder: "", required: true, rows: 3 };
    }
    return {
      field: field.field || field.name,
      title: field.title || field.label || field.name || field.field || "字段",
      type: field.type || "password",
      placeholder: field.placeholder || "",
      required: field.required !== false,
      rows: field.rows || 3,
    };
  });
}

// 解析 SSE 文本块，返回 [{ id, event, data }]；忽略注释行（heartbeat）
export function parseSseBlocks(text) {
  const blocks = String(text || "").split(/\r?\n\r?\n/);
  const out = [];
  for (const block of blocks) {
    let id;
    let eventName = "message";
    const dataLines = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith(":")) continue; // 注释 / 心跳
      if (line.startsWith("id:")) id = line.slice(3).trim();
      else if (line.startsWith("event:")) eventName = line.slice(6).trim();
      else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
    }
    if (dataLines.length) out.push({ id, event: eventName, data: dataLines.join("\n") });
  }
  return out;
}

// 事件类型 -> 会话状态（顺序不变量见设计文档 21.3）
export function sessionStatusAfterEvent(eventType, payload) {
  switch (eventType) {
    case "RUN_STARTED":
      return "RUNNING";
    case "RUN_SUSPENDED": {
      const reason = payload?.reason || "";
      if (reason === "WAITING_SECRET") return "WAITING_SECRET";
      if (reason === "WAITING_APPROVAL") return "WAITING_APPROVAL";
      if (reason === "WAITING_INPUT") return "WAITING_INPUT";
      return "SUSPENDED";
    }
    case "RUN_COMPLETED":
      return "COMPLETED";
    case "RUN_FAILED":
      return "FAILED";
    case "RUN_CANCELLED":
      return "CANCELLED";
    default:
      return null;
  }
}

// ===== Pinia store =====

export const useAgentConversationStore = defineStore("agentConversation", {
  state: () => ({
    sessionIndex: [], // [{ id, title, updatedAt, status }]
    currentSessionId: "",
    session: null, // { id, title, status, lastSeq, workingState }
    events: new Map(), // seq -> event（有序 Map，渲染时按 seq 排序）
    lastSeq: 0,
    pendingDelta: "", // assistant_delta 累积缓存
    pendingEvents: [], // 跳号补齐期间暂存的事件
    gapFilling: false,
    sending: false,
    streamState: "idle", // idle | connecting | open | reconnecting | closed
    backoffAttempt: 0,
    lastSseDataAt: 0, // 最近一次收到 SSE 字节的时间戳（看门狗续命依据）
    lastError: "",
    reconnectTimer: null,
    streamController: null,
  }),

  getters: {
    orderedEvents(state) {
      return Array.from(state.events.entries())
        .sort((a, b) => a[0] - b[0])
        .map(([, event]) => event);
    },
    currentSession(state) {
      return state.sessionIndex.find((item) => item.id === state.currentSessionId) || null;
    },
    isRunning(state) {
      return state.session?.status === "RUNNING";
    },
  },

  actions: {
    // ===== 本地会话索引 =====
    initFromStorage() {
      try {
        const raw = localStorage.getItem(SESSION_INDEX_KEY);
        const list = raw ? JSON.parse(raw) : [];
        this.sessionIndex = Array.isArray(list) ? list : [];
      } catch {
        this.sessionIndex = [];
      }
    },

    persistIndex() {
      try {
        localStorage.setItem(SESSION_INDEX_KEY, JSON.stringify(this.sessionIndex));
      } catch {
        // 索引只是会话恢复入口，失败不影响主流程
      }
    },

    upsertIndex(session) {
      const id = String(session.id);
      const found = this.sessionIndex.find((item) => item.id === id);
      const entry = {
        id,
        title: session.title || found?.title || "AI 会话",
        status: session.status || found?.status || "READY",
        updatedAt: new Date().toISOString(),
      };
      if (found) {
        Object.assign(found, entry);
      } else {
        this.sessionIndex.unshift(entry);
      }
      this.persistIndex();
    },

    // ===== 会话生命周期 =====
    async createSession(title) {
      const payload = { requestId: newRequestId(), title: title?.trim() || "AI 会话" };
      const res = await createAiSession(payload);
      const session = res?.session || res || {};
      this.resetEvents();
      this.session = {
        id: String(session.id),
        title: payload.title,
        status: session.status || "READY",
        lastSeq: Number(session.lastSeq) || 0,
        workingState: session.workingState || {},
      };
      this.lastSeq = this.session.lastSeq;
      this.upsertIndex(this.session);
      this.currentSessionId = this.session.id;
      this.resetStream();
      this.startStream();
      return this.session;
    },

    // 删除会话：级联清理本地状态；删除当前会话时切到最近剩余会话
    async deleteSession(sessionId) {
      const id = String(sessionId || "");
      if (!id) return;
      await deleteAiSession(id);
      this.sessionIndex = this.sessionIndex.filter((item) => item.id !== id);
      this.persistIndex();
      if (this.currentSessionId !== id) return;
      this.closeStream();
      this.resetEvents();
      this.currentSessionId = "";
      this.session = null;
      const next = this.sessionIndex[0]?.id;
      if (next) {
        await this.resumeSession(next).catch(() => {});
      }
    },

    // 页面恢复 / 切换会话：先 GET session，再从 lastSeq 补齐并续传 SSE
    async resumeSession(sessionId) {
      const id = String(sessionId);
      this.closeStream();
      this.streamState = "idle";
      this.backoffAttempt = 0;
      this.resetEvents();
      this.currentSessionId = id;
      await this.refreshSession();
      this.startStream();
    },

    async refreshSession() {
      const id = this.currentSessionId;
      if (!id) return;
      const res = await getAiSession(id);
      const session = res?.session || res || {};
      this.session = {
        id: String(session.id || id),
        title: session.title || this.currentSession?.title || "AI 会话",
        status: session.status || this.session?.status || "READY",
        lastSeq: session.lastSeq != null ? Number(session.lastSeq) : this.lastSeq,
        workingState: session.workingState || this.session?.workingState || {},
      };
      this.upsertIndex(this.session);
      // 本地事件落后于服务端时，用 JSON 接口补齐
      const localMax = this.maxLocalSeq();
      if (this.session.lastSeq > localMax) {
        this.lastSeq = localMax; // 游标回退到本地最大 seq，让补齐从该处续拉
        await this.fillGapUpTo(this.session.lastSeq);
      } else {
        this.lastSeq = this.session.lastSeq;
      }
    },

    maxLocalSeq() {
      let max = 0;
      for (const seq of this.events.keys()) {
        if (seq > max) max = seq;
      }
      return max;
    },

    resetEvents() {
      this.events = new Map();
      this.lastSeq = 0;
      this.pendingEvents = [];
      this.pendingDelta = "";
      this.gapFilling = false;
      this.lastError = "";
    },

    // ===== 消息 =====
    async sendMessage(text) {
      const id = this.currentSessionId;
      const content = String(text || "").trim();
      if (!id || !content) return null;
      this.sending = true;
      try {
        const res = await sendAiMessage(id, {
          requestId: newRequestId(),
          text: content,
          expectedLastSeq: this.lastSeq,
        });
        this.lastError = "";
        this.startStream();
        return res?.runId || null;
      } finally {
        this.sending = false;
      }
    },

    // ===== SSE =====
    resetStream() {
      this.closeStream();
      // closeStream 会把状态置为 closed（“主动关闭、不再重连”的语义），
      // 这里必须复位为 idle，否则紧接着的 startStream 会被 closed 短路而永不连接
      this.streamState = "idle";
      this.backoffAttempt = 0;
      this.lastError = "";
    },

    closeStream() {
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }
      if (this.streamController) {
        this.streamController.abort();
        this.streamController = null;
      }
      this.streamState = "closed";
    },

    scheduleReconnect(reason, failedController) {
      if (this.streamState === "closed") return;
      this.streamState = "reconnecting";
      this.lastError = reason || "连接中断，正在重连";
      const delay = nextBackoffMs(this.backoffAttempt);
      this.backoffAttempt += 1;
      if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        // 期间已有新连接（controller 被替换）时跳过本次重连，避免抖动
        if (failedController && this.streamController !== failedController) return;
        this.startStream();
      }, delay);
    },

    async startStream() {
      const id = this.currentSessionId;
      if (!id || this.streamState === "closed") return;
      if (this.streamController) this.streamController.abort();
      const controller = new AbortController();
      this.streamController = controller;
      this.streamState = "connecting";
      this.lastSseDataAt = Date.now();
      let watchdogFired = false;
      const watchdog = setInterval(() => {
        if (controller.signal.aborted) return;
        if (watchdogExpired(this.lastSseDataAt)) {
          watchdogFired = true;
          controller.abort(); // 无数据假死：中断当前连接，按退避重连
        }
      }, 5000);
      try {
        await this.streamLoop(id, controller);
      } catch (err) {
        if (controller.signal.aborted && !watchdogFired) return; // 主动关闭，不重连
        this.scheduleReconnect(err?.message || "SSE 连接失败", controller);
      } finally {
        clearInterval(watchdog);
      }
    },

    async streamLoop(id, controller) {
      const apiKey = localStorage.getItem("consilens-api-key") || "";
      const headers = { Accept: "text/event-stream" };
      if (apiKey) headers["X-Consilens-Api-Key"] = apiKey;
      const response = await fetch(`/v1/ai/sessions/${id}/events?afterSeq=${this.lastSeq}`, {
        headers,
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`SSE 响应异常（${response.status}）`);
      }
      if (!response.body) {
        throw new Error("SSE 响应无数据流");
      }
      this.streamState = "open";
      this.backoffAttempt = 0;
      this.lastSseDataAt = Date.now();
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        this.lastSseDataAt = Date.now(); // 任何字节（含 heartbeat）都续命看门狗
        buffer += decoder.decode(value, { stream: true });
        let match;
        while ((match = buffer.match(/\r?\n\r?\n/))) {
          const chunk = buffer.slice(0, match.index);
          buffer = buffer.slice(match.index + match[0].length);
          this.dispatchSseChunk(chunk);
        }
      }
      buffer += decoder.decode(); // 冲刷解码器剩余字节
      if (buffer.trim()) this.dispatchSseChunk(buffer);
      // 服务端正常关闭连接：按退避重连续传
      if (!controller.signal.aborted) {
        this.scheduleReconnect("事件流已关闭，正在续传", controller);
      }
    },

    dispatchSseChunk(chunk) {
      const parsed = parseSseBlocks(chunk);
      for (const item of parsed) {
        if (!item.data) continue;
        let payload;
        try {
          payload = JSON.parse(item.data);
        } catch {
          continue; // 非 JSON 数据（如纯文本心跳）忽略
        }
        if (Array.isArray(payload)) {
          payload.forEach((event) => this.handleEvent(event));
        } else if (payload && typeof payload === "object") {
          this.handleEvent(payload);
        }
      }
    },

    // ===== 事件去重 / 跳号补齐 =====
    handleEvent(event) {
      const seq = toSeq(event);
      if (seq === null) return;
      if (isDuplicateEvent(seq, this.lastSeq)) return; // 以 seq 为 key 去重
      this.pendingEvents.push(event);
      if (seq === this.lastSeq + 1) {
        this.drainPending();
      } else if (!this.gapFilling) {
        // 跳号：暂停乐观展示，用 JSON events 补齐
        this.gapFilling = true;
        this.fillGapUpTo(seq).finally(() => this.drainPending());
      }
    },

    drainPending() {
      if (!this.pendingEvents.length) return;
      const sorted = [...this.pendingEvents].sort((a, b) => toSeq(a) - toSeq(b));
      const rest = [];
      for (const event of sorted) {
        const seq = toSeq(event);
        if (seq === null || isDuplicateEvent(seq, this.lastSeq)) continue;
        if (seq > this.lastSeq + 1) {
          rest.push(event);
          continue;
        }
        this.applyEvent(event);
      }
      this.pendingEvents = rest;
      if (rest.length && !this.gapFilling) {
        this.gapFilling = true;
        this.fillGapUpTo(toSeq(rest[0])).then((ok) => {
          if (!ok) {
            // 服务端存在缺号（异常态）：丢弃无法衔接的事件，避免无限补齐
            this.pendingEvents = this.pendingEvents.filter(
              (event) => toSeq(event) > this.lastSeq && toSeq(event) <= this.lastSeq + 1
            );
          }
          this.drainPending();
        }).catch(() => this.drainPending());
      }
    },

    // 拉取 JSON 事件直到覆盖 targetSeq（有页数护栏，防止异常后端导致死循环）
    async fillGapUpTo(targetSeq) {
      const id = this.currentSessionId;
      const pageSize = 200;
      let page = 1;
      let guard = 0;
      try {
        while (this.lastSeq < targetSeq && guard < 50) {
          guard += 1;
          const before = this.lastSeq;
          const res = await fetchAiEvents(id, { afterSeq: this.lastSeq, page, pageSize });
          const list = extractEventList(res);
          if (!list.length) break;
          for (const event of list) {
            const seq = toSeq(event);
            if (seq === null || isDuplicateEvent(seq, this.lastSeq)) continue;
            this.applyEvent(event); // 统一走事件管道：状态迁移 / delta 缓存一致
          }
          if (this.lastSeq === before) break; // 返回了空档：停止，避免死循环
          if (list.length < pageSize) break; // 已到最后一页
          page += 1;
        }
        return this.lastSeq >= targetSeq;
      } finally {
        this.gapFilling = false;
      }
    },

    applyEvent(event) {
      const seq = toSeq(event);
      if (seq === null) return;
      this.events.set(seq, event);
      this.lastSeq = seq;
      this.lastError = "";
      const type = event.type || "";
      const payload = event.payload || {};
      if (type === "ASSISTANT_DELTA") {
        this.pendingDelta += String(payload.delta || "");
      } else if (type === "ASSISTANT_MESSAGE") {
        this.pendingDelta = ""; // 最终文本替换流式缓存
      }
      const status = sessionStatusAfterEvent(type, payload);
      if (status && this.session) {
        this.session.status = status;
        this.upsertIndex(this.session);
      }
      if (type === "WORKING_STATE_PATCHED" && this.session) {
        this.session.workingState = {
          ...(this.session.workingState || {}),
          stage: payload.newStage || this.session.workingState?.stage,
        };
      }
    },

    // ===== 审批 / 取消 / 重试 =====
    async decideApproval(approvalId, decision, approval) {
      const id = this.currentSessionId;
      if (!id || !approvalId) return;
      return decideAiApproval(id, approvalId, {
        requestId: newRequestId(),
        decision,
        actionDigest: approval?.actionDigest,
        version: approval?.version,
      });
    },

    async cancelRun() {
      const id = this.currentSessionId;
      if (!id) return;
      await cancelAiRun(id);
      await this.refreshSession();
      this.startStream();
    },

    async retryRun() {
      const id = this.currentSessionId;
      if (!id) return;
      await retryAiRun(id, { requestId: newRequestId() });
      await this.refreshSession();
      this.startStream();
    },
  },
});
