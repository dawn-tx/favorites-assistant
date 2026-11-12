// 平板收藏助手的本地 mock 同步服务：只用 Node 内置模块，不需要 npm install。
//
//   node mock-server/server.mjs
//
// 它接收 App 发出的信封加密请求（AES-256-GCM 数据 + RSA-OAEP 包裹的会话密钥），
// 解出明文后把收藏写进 mock-server/data.json，用来在本地完整验证「保存同步 / 断网排队 / 联网补传」链路。
//
// 环境变量：
//   PORT                 监听端口，默认 8787
//   BASE_PATH            路由前缀，默认 /api/v1（要和 App 设置里的接口地址一致）
//   SYNC_TOKEN           设置后要求 Authorization: Bearer <token>，用来演示 4xx 拒绝分支
//   DATA_FILE            数据落盘位置，默认 mock-server/data.json
//   PRIVATE_KEY_FILE     RSA 私钥 PEM，默认 mock-server/server_private_key.pem

import {
  constants,
  createDecipheriv,
  privateDecrypt,
} from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT ?? 8787);
const BASE_PATH = (process.env.BASE_PATH ?? '/api/v1').replace(/\/+$/, '');
const TOKEN = process.env.SYNC_TOKEN ?? '';
const DATA_FILE = process.env.DATA_FILE ?? join(ROOT, 'data.json');
const PRIVATE_KEY_FILE = process.env.PRIVATE_KEY_FILE ?? join(ROOT, 'server_private_key.pem');
const DEVICE_ALG = 'AES-256-GCM/DEVICE-KEYSTORE';

let privateKey = null;
if (existsSync(PRIVATE_KEY_FILE)) {
  privateKey = readFileSync(PRIVATE_KEY_FILE, 'utf8');
}

const state = loadData();

function loadData() {
  try {
    const parsed = JSON.parse(readFileSync(DATA_FILE, 'utf8'));
    return { favorites: {}, devices: {}, ...parsed };
  } catch {
    return { favorites: {}, devices: {} };
  }
}

function saveData() {
  writeFileSync(DATA_FILE, JSON.stringify(state, null, 2));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 4 * 1024 * 1024) {
        reject(new Error('payload too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function send(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
  });
  res.end(body);
}

/** 解开出站信封；设备密钥模式下服务端拿不到明文，只确认收到 */
function openEnvelope(envelope) {
  if (!envelope || typeof envelope.data !== 'string' || typeof envelope.iv !== 'string') {
    throw new Error('信封格式不正确');
  }
  if (envelope.alg === DEVICE_ALG) {
    return { readable: false, reason: '设备本地密钥模式，服务端无法解密（内容仍完整保存在设备侧队列）' };
  }
  if (!privateKey) {
    throw new Error('缺少私钥：先运行 node mock-server/keygen.mjs 生成密钥对，再把公钥填进 App 设置');
  }
  const sessionKey = privateDecrypt(
    { key: privateKey, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' },
    Buffer.from(envelope.key ?? '', 'base64'),
  );
  const raw = Buffer.from(envelope.data, 'base64');
  const tag = raw.subarray(raw.length - 16);
  const cipherText = raw.subarray(0, raw.length - 16);
  const decipher = createDecipheriv('aes-256-gcm', sessionKey, Buffer.from(envelope.iv, 'base64'));
  decipher.setAuthTag(tag);
  const plain = Buffer.concat([decipher.update(cipherText), decipher.final()]).toString('utf8');
  return { readable: true, request: JSON.parse(plain) };
}

function applyOps(request) {
  let applied = 0;
  const unknown = [];
  for (const op of request.ops ?? []) {
    if (op.type === 'UPSERT' && op.favorite?.id) {
      state.favorites[op.favorite.id] = {
        ...op.favorite,
        device_id: request.device_id,
        updated_at: Date.now(),
      };
      applied += 1;
      console.log(`  UPSERT ${op.favorite.id}  ${op.favorite.url}  tags=[${(op.favorite.tags ?? []).join(',')}]`);
    } else if (op.type === 'DELETE' && op.favorite_id) {
      if (state.favorites[op.favorite_id]) {
        delete state.favorites[op.favorite_id];
        applied += 1;
        console.log(`  DELETE ${op.favorite_id}`);
      } else {
        unknown.push(op.favorite_id);
      }
    } else {
      unknown.push(op.favorite?.id ?? op.favorite_id ?? 'unknown');
    }
  }
  state.devices[request.device_id] = { last_sync_at: Date.now(), favorites: Object.keys(state.favorites).length };
  return { applied, unknown };
}

function listFavorites() {
  return Object.values(state.favorites).map((row) => ({
    id: row.id,
    url: row.url,
    title: row.title,
    note: row.note,
    source_app: row.source_app,
    favorite_at: row.favorite_at,
    last_position_ms: row.last_position_ms,
    duration_ms: row.duration_ms,
    bucket: row.bucket ?? 'INBOX',
    tags: row.tags ?? [],
  }));
}

function authorized(req) {
  if (!TOKEN) return true;
  return (req.headers.authorization ?? '') === `Bearer ${TOKEN}`;
}

const server = createServer(async (req, res) => {
  const path = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`).pathname.replace(/\/+$/, '');

  if (path === `${BASE_PATH}/health`) {
    send(res, 200, { ok: true, server_time: Date.now(), private_key: privateKey ? 'loaded' : 'missing' });
    return;
  }
  if (path === `${BASE_PATH}/stats`) {
    send(res, 200, { ok: true, favorites: Object.keys(state.favorites).length, devices: state.devices });
    return;
  }
  if (path === `${BASE_PATH}/favorites` && req.method === 'GET') {
    if (!authorized(req)) return send(res, 401, { ok: false, error: 'unauthorized' });
    send(res, 200, { ok: true, server_time: Date.now(), applied: 0, favorites: listFavorites() });
    return;
  }
  if (path === `${BASE_PATH}/sync` && req.method === 'POST') {
    if (!authorized(req)) return send(res, 401, { ok: false, error: 'unauthorized' });
    let outcome;
    try {
      const envelope = JSON.parse(await readBody(req));
      outcome = openEnvelope(envelope);
      if (!outcome.readable) {
        console.log(`POST /sync  ${envelope.alg} -> ${outcome.reason}`);
        send(res, 200, { ok: true, server_time: Date.now(), applied: 0, unknown_ops: [], favorites: [] });
        return;
      }
      const { applied, unknown } = applyOps(outcome.request);
      saveData();
      console.log(`POST /sync  device=${outcome.request.device_id} ops=${applied + unknown.length} applied=${applied}`);
      send(res, 200, { ok: true, server_time: Date.now(), applied, unknown_ops: unknown, favorites: [] });
    } catch (error) {
      console.error(`POST /sync 失败: ${error.message}`);
      send(res, 400, { ok: false, error: error.message, applied: 0, unknown_ops: [], favorites: [] });
    }
    return;
  }

  send(res, 404, {
    ok: false,
    error: 'not found',
    endpoints: [`${BASE_PATH}/health`, `${BASE_PATH}/sync`, `${BASE_PATH}/favorites`, `${BASE_PATH}/stats`],
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`收藏助手 mock 服务已启动: http://127.0.0.1:${PORT}${BASE_PATH}`);
  console.log(`模拟器内请填写 http://10.0.2.2:${PORT}${BASE_PATH}`);
  console.log(privateKey ? '已加载 RSA 私钥，可解密信封同步内容' : '未加载 RSA 私钥（运行 node mock-server/keygen.mjs 生成）');
  if (TOKEN) console.log(`已开启 Bearer 校验，App 设置里需填同样的令牌`);
});
