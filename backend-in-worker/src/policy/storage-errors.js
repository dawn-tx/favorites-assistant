/** 存储服务限流：调用方按 retryAfterMs 延后，不记普通失败。 */
export class StorageThrottleError extends Error {
  constructor(message, retryAfterMs) {
    super(message);
    this.name = 'StorageThrottleError';
    this.retryAfterMs = retryAfterMs;
  }
}

export function parseRetryAfter(value, fallbackMs) {
  if (!value) return fallbackMs;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.round(seconds * 1000);
  const date = Date.parse(value);
  if (Number.isFinite(date)) return Math.max(0, date - Date.now());
  return fallbackMs;
}