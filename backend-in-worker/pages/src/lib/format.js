const dateTime = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
});

const fullDateTime = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});

export function formatTime(ms) {
  if (!ms) return '—';
  return dateTime.format(new Date(Number(ms)));
}

export function formatFullTime(ms) {
  if (!ms) return '—';
  return fullDateTime.format(new Date(Number(ms)));
}

export function formatRelative(ms, now = Date.now()) {
  if (!ms) return '—';
  const delta = Number(ms) - now;
  const abs = Math.abs(delta);
  const suffix = delta >= 0 ? '后' : '前';
  if (abs < 60_000) return `${Math.max(1, Math.round(abs / 1000))} 秒${suffix}`;
  if (abs < 3_600_000) return `${Math.round(abs / 60_000)} 分钟${suffix}`;
  if (abs < 86_400_000) return `${Math.round(abs / 3_600_000)} 小时${suffix}`;
  return `${Math.round(abs / 86_400_000)} 天${suffix}`;
}

export function formatBytes(value) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes <= 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}

export function stateLabel(state) {
  return {
    ready: '就绪',
    pending: '处理中',
    failed: '失败',
    none: '未准备',
    partial: '部分完成',
    unknown: '未知',
  }[state] ?? state ?? '未知';
}

export function percent(part, total) {
  if (!total) return 0;
  return Math.max(0, Math.min(100, Math.round((part / total) * 100)));
}