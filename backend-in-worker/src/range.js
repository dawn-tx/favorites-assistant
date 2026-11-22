/**
 * Range 头解析：返回 null（无 Range）、'invalid'、或闭区间 { start, end }。
 * 媒体代理的整片透传、单片合成与备份回落共用这一份，避免三处各写一套。
 */
export function parseRangeHeader(header, size) {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(String(header).trim());
  if (!match) return null;
  const hasStart = match[1] !== '';
  const hasEnd = match[2] !== '';
  if (!hasStart && !hasEnd) return null;
  if (!hasStart) {
    const suffix = Number(match[2]);
    if (!Number.isFinite(suffix) || suffix <= 0 || size <= 0) return 'invalid';
    return { start: Math.max(0, size - suffix), end: size - 1 };
  }
  const start = Number(match[1]);
  if (!Number.isFinite(start) || start < 0 || start >= size) return 'invalid';
  let end = hasEnd ? Number(match[2]) : size - 1;
  if (!Number.isFinite(end)) return 'invalid';
  end = Math.min(end, size - 1);
  if (end < start) return 'invalid';
  return { start, end };
}
