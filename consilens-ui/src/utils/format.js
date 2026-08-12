import dayjs from "dayjs";

export function formatTime(value, pattern = "YYYY-MM-DD HH:mm:ss") {
  if (!value) return "-";
  return dayjs(value).format(pattern);
}

export function formatDate(value) {
  return formatTime(value, "YYYY-MM-DD");
}

export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) return "-";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export function formatNumber(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return "-";
  return Number(value).toLocaleString("en-US");
}

// differencePercentage 为 0..100 语义（与生成侧 ChecksumDiffer/JoinDiffer 一致），直接加 % 展示
export function formatRatio(value, digits = 1) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return "-";
  return `${Number(value).toFixed(digits)}%`;
}

// 主键统一为可展示字符串（数组 join / 对象 JSON / 标量原样）
export function parsePrimaryKey(primaryKey) {
  if (primaryKey === null || primaryKey === undefined) return "-";
  if (Array.isArray(primaryKey)) return primaryKey.map(String).join(", ");
  if (typeof primaryKey === "object") return JSON.stringify(primaryKey);
  return String(primaryKey);
}

export function formatDuration(start, end) {
  if (!start || !end) return "-";
  const ms = dayjs(end).diff(dayjs(start));
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60 * 1000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60000)} m ${Math.round((ms % 60000) / 1000)} s`;
}

export function formatLatency(ms) {
  if (ms === null || ms === undefined) return "-";
  return `${ms} ms`;
}

export function buildPageParams(page, pageSize, filters = {}) {
  const params = { page, pageSize };
  Object.entries(filters).forEach(([key, value]) => {
    if (value === null || value === undefined || value === "" ) return;
    if (Array.isArray(value)) {
      if (value.length) params[key] = value;
    } else if (typeof value === "number" && value > 100000000000) {
      // naive-ui date-picker 输出毫秒时间戳 → ISO-8601
      params[key] = dayjs(value).toISOString();
    } else {
      params[key] = value;
    }
  });
  return params;
}

// 解析 RUN_RESULT artifact 内容
export function parseRunResultContent(content) {
  if (typeof content === "string") {
    try {
      content = JSON.parse(content);
    } catch {
      return null;
    }
  }
  if (!content || typeof content !== "object") return null;
  return {
    statistics: content.statistics || null,
    differences: Array.isArray(content.differences) ? content.differences : [],
    sampleSize: content.differenceSampleSize || null,
    totalDifferences: content.totalDifferenceCount ?? content.differences?.length ?? 0,
  };
}

export function generateSerialNo() {
  return `consilens-${Date.now()}`;
}
