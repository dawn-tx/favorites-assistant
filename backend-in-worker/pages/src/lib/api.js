const API_PREFIX = '/admin/api';

export async function getJson(path) {
  const response = await fetch(`${API_PREFIX}${path}`, {
    headers: { accept: 'application/json' },
    cache: 'no-store',
  });
  const data = await response.json().catch(() => null);
  if (!response.ok || !data?.ok) {
    throw new Error(data?.error || `请求失败：HTTP ${response.status}`);
  }
  return data;
}

export function absoluteMediaUrl(base, path) {
  if (!path) return '';
  if (/^https?:\/\//i.test(path)) return path;
  return `${String(base || '').replace(/\/+$/, '')}${path.startsWith('/') ? path : `/${path}`}`;
}

export async function probeMedia(url) {
  const startedAt = Date.now();
  try {
    const response = await fetch(url, { method: 'HEAD' });
    return {
      ok: response.ok,
      status: response.status,
      cacheStatus: response.headers.get('cf-cache-status') || '',
      age: response.headers.get('age') || '',
      contentLength: response.headers.get('content-length') || '',
      acceptRanges: response.headers.get('accept-ranges') || '',
      elapsedMs: Date.now() - startedAt,
      checkedAt: Date.now(),
      error: '',
    };
  } catch (error) {
    return {
      ok: false,
      status: 0,
      cacheStatus: '',
      age: '',
      contentLength: '',
      acceptRanges: '',
      elapsedMs: Date.now() - startedAt,
      checkedAt: Date.now(),
      error: error.message || '请求失败',
    };
  }
}