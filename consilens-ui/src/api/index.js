import axios from "axios";

const request = axios.create({
  baseURL: "/v1",
  timeout: 30000,
  // 数组参数序列化为多值形式（status=A&status=B），匹配 Spring @RequestParam List<T>
  paramsSerializer: { indexes: null },
});

// 由 App.vue 在挂载后注入 message 实例（naive-ui）
let messageApi = null;
export function setupMessage(message) {
  messageApi = message;
}

function notify(error, silent) {
  if (silent || !messageApi) return;
  messageApi.error(error.message || "请求失败");
}

request.interceptors.request.use((config) => {
  const apiKey = localStorage.getItem("consilens-api-key");
  if (apiKey) {
    config.headers["X-Consilens-Api-Key"] = apiKey;
  }
  return config;
});

request.interceptors.response.use(
  (response) => {
    const body = response.data;
    if (body && body.success === false) {
      const error = new Error(body.message || body.errorCode || "request failed");
      error.errorCode = body.errorCode;
      error.traceId = body.traceId;
      error.raw = body;
      notify(error, response.config?.silent);
      return Promise.reject(error);
    }
    return body && typeof body === "object" && "data" in body ? body.data : body;
  },
  (error) => {
    const status = error.response?.status;
    let message = error.message || "网络请求失败";
    if (status === 404) {
      message = "资源不存在";
    } else if (status >= 500) {
      message = "服务器内部错误";
    } else if (!error.response) {
      message = "无法连接到服务器";
    }
    const normalized = new Error(message);
    normalized.status = status;
    normalized.errorCode = error.response?.data?.errorCode;
    notify(normalized, error.config?.silent);
    return Promise.reject(normalized);
  }
);

export default request;
