/**
 * 只用 WebCrypto：Workers 里没有 node:crypto，虚拟 key 与内容版本都在这层派生。
 */
const encoder = new TextEncoder();
const hmacKeys = new Map();

function toBytes(input) {
  return typeof input === 'string' ? encoder.encode(input) : input;
}

function toHex(buffer) {
  const bytes = new Uint8Array(buffer);
  let out = '';
  for (const byte of bytes) out += byte.toString(16).padStart(2, '0');
  return out;
}

export async function sha256Hex(input) {
  return toHex(await crypto.subtle.digest('SHA-256', toBytes(input)));
}

export async function hmacSha256Hex(secret, input) {
  let key = hmacKeys.get(secret);
  if (!key) {
    key = await crypto.subtle.importKey(
      'raw',
      encoder.encode(secret),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign'],
    );
    hmacKeys.set(secret, key);
  }
  return toHex(await crypto.subtle.sign('HMAC', key, toBytes(input)));
}

export function base64ToBytes(text) {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
