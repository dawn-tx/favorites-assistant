// WebSocket 中枢：消息协议、推送分帧、以及 /sync 与解析完成后的 notify。
//   node --test backend-in-worker/test/ws.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker, { LibraryHub } from '../src/index.js';
import { parserConfig } from '../src/policy/parser.js';
import { call, createFakeHub, createTestEnv, tick } from './support/harness.mjs';
import { listen, startOrigin } from './support/origin.mjs';

let origin;
let originBase;

before(async () => {
  origin = startOrigin();
  const port = await listen(origin.server);
  originBase = `http://127.0.0.1:${port}`;
  parserConfig.url = `${originBase}/parse`;
});

after(async () => {
  await new Promise((resolve) => origin.server.close(resolve));
});

function fakeSocket() {
  return {
    sent: [],
    send(text) {
      this.sent.push(JSON.parse(text));
    },
    close() {},
  };
}

function fakeCtx(sockets = []) {
  return {
    getWebSockets: () => sockets,
    acceptWebSocket() {},
  };
}

function newHub(env, sockets = []) {
  return { hub: new LibraryHub(fakeCtx(sockets), env), sockets };
}

async function send(hub, socket, payload) {
  await hub.webSocketMessage(socket, JSON.stringify(payload));
  return socket.sent[socket.sent.length - 1];
}

const base = '/api/v1';
const request = (env, method, path, body) => call(worker, env, method, `${base}${path}`, body);

test('hello / ping / 未知类型', async () => {
  const env = createTestEnv();
  const { hub } = newHub(env);
  const socket = fakeSocket();
  assert.equal((await send(hub, socket, { id: 1, t: 'hello', device_id: 'tab' })).t, 'hello');
  assert.equal((await send(hub, socket, { id: 2, t: 'ping' })).t, 'pong');
  const unknown = await send(hub, socket, { id: 3, t: 'nope' });
  assert.equal(unknown.t, 'error');
  assert.match(unknown.error, /未知消息类型/);
});

test('list / lookup / resolve 与 HTTP 返回同一形状', async () => {
  const env = createTestEnv({ JOBS_PER_TICK: '0', UPLOAD_JOBS_PER_TICK: '0' });
  const url = 'https://example.com/ws-list';
  const synced = await request(env, 'POST', '/sync', {
    device_id: 'tab',
    ops: [{ type: 'UPSERT', favorite: { url, note: 'ws', tags: ['a'], source_app: 'manual', favorite_at: Date.now() } }],
  });
  assert.equal(synced.status, 200);

  const { hub } = newHub(env);
  const socket = fakeSocket();
  const listed = await send(hub, socket, { id: 10, t: 'list', fields: 'min', limit: 10 });
  assert.equal(listed.t, 'list');
  assert.equal(listed.favorites.length, 1);
  assert.deepEqual(Object.keys(listed.favorites[0]).sort(), ['favorite_at', 'media_state', 'updated_at', 'url']);

  const looked = await send(hub, socket, { id: 11, t: 'lookup', urls: [url] });
  assert.equal(looked.favorites[0].note, 'ws');

  const resolved = await send(hub, socket, { id: 12, t: 'resolve', urls: [url, 'https://nope.local/x'] });
  assert.deepEqual(resolved.unknown_urls, ['https://nope.local/x']);
  assert.equal(resolved.favorites[0].media_state, 'pending');
});

test('错误消息带 id 与原因', async () => {
  const env = createTestEnv();
  const { hub } = newHub(env);
  const socket = fakeSocket();
  const badCursor = await send(hub, socket, { id: 20, t: 'list', cursor: 'abc' });
  assert.equal(badCursor.t, 'error');
  assert.match(badCursor.error, /cursor/);
  const badUrls = await send(hub, socket, { id: 21, t: 'lookup', urls: 'x' });
  assert.equal(badUrls.t, 'error');
});

test('notify 按帧广播完整收藏与删除；没有连接时静默', async () => {
  const env = createTestEnv();
  const a = fakeSocket();
  const b = fakeSocket();
  const { hub } = newHub(env, [a, b]);
  const favorites = Array.from({ length: 25 }, (_, i) => ({ url: `https://example.com/${i}` }));

  const response = await hub.fetch(
    new Request('https://hub/notify', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ favorites, deleted: ['https://example.com/gone'] }),
    }),
  );
  assert.equal(response.status, 200);
  for (const socket of [a, b]) {
    const favoritesFrames = socket.sent.filter((item) => item.t === 'favorites');
    assert.equal(favoritesFrames.length, 2, '25 条应按 20 条一帧切成 2 帧');
    assert.equal(favoritesFrames[0].favorites.length, 20);
    assert.equal(favoritesFrames[1].favorites.length, 5);
    assert.deepEqual(socket.sent.find((item) => item.t === 'deleted').urls, ['https://example.com/gone']);
  }

  const empty = newHub(createTestEnv(), []);
  const quiet = await empty.hub.fetch(new Request('https://hub/notify', { method: 'POST', body: '{}' }));
  assert.equal(quiet.status, 200);
});

test('/sync 写入后通知中枢；删除推 deleted', async () => {
  const fake = createFakeHub();
  const env = createTestEnv({ JOBS_PER_TICK: '0', UPLOAD_JOBS_PER_TICK: '0', LIB: fake.binding });
  const url = 'https://example.com/ws-notify';
  await request(env, 'POST', '/sync', {
    device_id: 'tab',
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
  assert.ok(fake.calls.some((call) => call.body?.favorites?.some((item) => item.url === url && item.media_state === 'pending')));

  await request(env, 'POST', '/sync', { device_id: 'tab', ops: [{ type: 'DELETE', url }] });
  assert.ok(fake.calls.some((call) => call.body?.deleted?.includes(url)));
});

test('解析完成后推送 ready 完整行', async () => {
  const fake = createFakeHub();
  const env = createTestEnv({
    SLICE_SERVICE_URL: '',
    UPLOAD_JOBS_PER_TICK: '0',
    JOBS_PER_TICK: '4',
    LIB: fake.binding,
  });
  const url = 'https://example.com/ws-ready';
  await request(env, 'POST', '/sync', {
    device_id: 'tab',
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
  for (let i = 0; i < 20; i += 1) {
    await tick(worker, env);
    const ready = fake.calls.find((call) =>
      call.body?.favorites?.some((item) => item.url === url && item.media_state === 'ready' && item.media?.segments?.length),
    );
    if (ready) return;
  }
  throw new Error('解析完成后没有收到 ready 推送');
});