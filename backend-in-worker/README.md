# 收藏助手后台 · Cloudflare Worker 版

与 [mock-server](../mock-server/server.mjs) **同一套 HTTP 协议**（见 [同步后台接口说明](../docs/同步后台接口说明.md)），
差别只在部署形态：没有常驻进程、没有本地磁盘，元数据落 D1，媒体备份交给**外部存储服务**转存，
后台任务由 cron 驱动。App 不需要改字段或地址，只要把设置页的接口地址换成
`https://<worker>.workers.dev/api/v1`。

## 目录

```
backend-in-worker/
├── wrangler.toml            Worker 配置：D1、cron、vars
├── src/
│   ├── index.js             入口：路由分发 + cron 入口
│   ├── config.js            参数与默认值（与 mock 对齐）
│   ├── http.js              JSON 响应、请求体、鉴权、游标
│   ├── validate.js          字段校验
│   ├── hash.js              WebCrypto（SHA-256 / HMAC / base64）
│   ├── keys.js              虚拟 key、内容版本、内容指纹
│   ├── render.js            publicRow / minRow / segments
│   ├── parser.js            解析服务适配（唯一认识原始字段的地方）
│   ├── slice-service.js     远程切片服务 + 单飞
│   ├── probe.js             源探活（HEAD → Range 回退，带缓存）
│   ├── parse.js             解析编排：选档 → 探活 → 切片 → 建 assets
│   ├── range.js             Range 头解析
│   ├── budget.js            子请求预算（免费版 50 次/调用）
│   ├── media/
│   │   ├── storage.js       外部存储适配：上传 /（下载与删除的框架）
│   │   └── proxy.js         媒体代理：源站 / 单片合成 / 存储备份回落
│   ├── store/
│   │   ├── schema.sql       D1 建表
│   │   ├── mappers.js       D1 行 <-> 领域对象
│   │   ├── favorites.js     收藏读写与分页
│   │   ├── assets.js        资产读写
│   │   └── jobs.js          任务队列（D1 + 锁）
│   ├── handlers/            sync / favorites / resolve / ingest / meta
│   └── scheduled/runner.js  解析任务、备份任务、切片清理、删收藏
└── test/                    node:sqlite 模拟 D1、桥接 + 桩存储跑完整链路
```

## 外部存储怎么接

上传时**不传源站地址**，而是把本 Worker 的虚拟媒体地址交给存储服务：

```
POST {STORAGE_UPLOAD_URL}
[{ "type": "video", "media": "https://<worker>/api/v1/media/<key>.mp4" },
 { "type": "image", "media": "https://<worker>/api/v1/media/<key2>.jpg" }]

→ ["id1", "id2"]        // 按上传顺序，单次最多 10 条
```

- 每个切片也是独立文件：Worker 的虚拟地址本来就是 `init + 源区间` 合成后的完整片，
  所以存储服务拿到的就是可直接播放的字节。
- 整批失败（任一条回源失败、非 2xx、id 数量不符）就按整条任务退避重试；
  收藏本身仍是 `ready`，失败只体现在 `upload_state` 与 asset 的 `error`。
- 返回的 id 原样写进 `assets.store_id`，App 的 `segments[].store_id` 会带出来（App 不读，排障用）。
- **暂不处理重复**：同一文件重复上传会拿到新 id，以后接下载/删除时再按需要加本地索引。

`src/media/storage.js` 里的 `downloadStoredMedia()` / `deleteStoredMedia()` 目前是空框架：

- 下载接上后，`GET /media/<key>` 在源站失效时会直接透传存储服务返回的文件（需支持 Range / HEAD）；
  在接入之前，源站失效会明确回 `502`，不会假装有备份。
- 删除接上后，删收藏/重新解析会在行删除之后调用它清理远端文件；现在为 no-op。

## 数据压缩（D1）

D1 只存元数据，表结构按「重复数据只留一份」组织：

- `favorites` 不存段 key 列表；段顺序由 `assets.ord` 决定。
- `sources(id, url, etag, file_size, quality)`：同一内容版本的文件信息全局一行，切片只存 `source_id`。
- `inits(id, hash, bytes BLOB)`：切片 init 段按内容哈希共享，`assets` 只存 `init_id` 与字节区间；
  init 存**原始字节**，不存 base64（省 33% 膨胀，解析时只解码一次）。
- `assets` 只存 `favorite_id`，不再每行重复收藏 url。
- 切片信息空闲超过 TTL 后清掉 `init_id/区间`；每天一次的 cron 回收无引用的 `inits` 与 `sources`
  （这两个回收要扫 assets 表，放每天跑是为了不吃满 D1 免费版的行读取配额）。

同一测试夹具（1 视频 2 片 + 1 图 + 1 封面）实测：旧结构约 1.29KB，新结构约 0.69KB。
注意：真实切片每片的 `init` 通常不同（`moov` 会按片重写），`inits` 去重主要作用于重复解析与跨收藏复用；
热态仍约 1.5KB/片（init 存原始字节），20 片约 30KB、50 片约 75KB；切片信息清理后约 4KB/条。

## 部署（不在本地安装 Wrangler）

以下命令都在有 Wrangler 的环境执行（CI、Cloud Shell 或你自己的机器）：

```bash
# 1) 建 D1，把 id 填进 wrangler.toml
wrangler d1 create favorites

# 2) 建表
wrangler d1 execute favorites --remote --file=src/store/schema.sql

# 3) secrets
wrangler secret put SYNC_TOKEN
wrangler secret put MEDIA_KEY_SECRET
wrangler secret put PARSE_SERVICE_TOKEN
wrangler secret put STORAGE_TOKEN

# 4) 在 wrangler.toml 里填：
#    PARSE_SERVICE_URL / SLICE_SERVICE_URL
#    MEDIA_BASE_URL      —— 部署后的 Worker 公网地址（含 /api/v1），存储服务要能访问
#    STORAGE_UPLOAD_URL  —— 外部存储的上传接口

# 5) 部署
wrangler deploy
```

部署后先看两个免鉴权接口：

```bash
curl -s https://<worker>.workers.dev/api/v1/health
curl -s https://<worker>.workers.dev/api/v1/meta
```

## 与 mock-server 的行为差异

| 方面 | mock-server | Worker 版 |
|---|---|---|
| 元数据 | `data.json` 单文件 | D1 `favorites` / `assets` / `jobs` |
| 媒体备份 | `store/` 本地文件 | 外部存储服务；上传虚拟媒体地址，拿回 id |
| 后台任务 | 进程内单线程循环、`setInterval` | D1 任务队列 + 每分钟 cron；写入后用 `waitUntil` 抢跑一次 |
| 切片清理 | `setInterval` | 每小时第 23 分的 cron |
| 孤儿回收 | 不需要 | 每天 04:41 的 cron 回收无引用的 `inits` / `sources` |
| 重启续跑 | 读 `jobs` 继续 | 任务在 D1，cron 自动继续 |

协议面完全一致：响应字段、游标、状态机、`retry-after` / `next_wait_ms`、媒体相对地址与
`Cache-Control: public, max-age=31536000, immutable` 都不变。

## 免费版额度怎么用

Workers 免费版：**每天 10 万次请求、每次调用 10ms CPU、最多 50 个子请求**；
D1 免费：单库 500MB、每次调用最多 50 次查询。媒体字节不再经过 Worker 存储，也不占 KV 写配额。

- **一次 tick 只跑一小批**：`JOBS_PER_TICK=4`、`UPLOAD_JOBS_PER_TICK=2`、`UPLOAD_ITEMS_PER_TICK=20`，
  调度器内部还有 45 个子请求的预算（`src/budget.js`）。预算见底时任务原样放回、**不记失败次数**，
  下一分钟接着跑；解析链路每次真正发请求前都会扣预算，不会把调用打到 1102。
- **上传很省子请求**：一批 ≤10 条只算 1 次外部调用，复制工作由存储服务完成。
  18MB/片时一批约 180MB，上传任务锁 `UPLOAD_JOB_LOCK_MS=10 分钟`、存储超时 `STORAGE_TIMEOUT_MS=5 分钟`，
  避免复制未完成就被下一轮 cron 重复领取。
- **切片上限按收藏算**：`MAX_SLICES_PER_FAVORITE=50`，超出片数预算的视频退回整片一段。
  按 18MB/片、20–50 片估，单条收藏热态约 30–80KB（D1），免费版单库 500MB 能放约 6 千–1.5 万条。
- **D1 写尽量合并**：解析落库、删收藏、回调批量插入都走 `db.batch()`（一次调用只算 1 次查询）。

## 已知边界

1. **存储服务必须能访问 `MEDIA_BASE_URL`**：它按虚拟地址回源；如果 Worker 前面套了 Access 或私有网络，这条路会断。
   虚拟地址里的不可猜 key 就是凭据，媒体接口本身免鉴权（播放器/Coil 不带 header）。
2. **下载/删除还没接**：源站失效时目前回 `502`；删收藏不会清远端文件。框架在 `src/media/storage.js`。
3. **后台不是实时**：解析最多滞后一个 cron 周期（60 秒）；写入接口会 `waitUntil` 抢跑一次，
   但免费版仍可能被 CPU 预算截断，没跑完的由 cron 接着跑。
4. **D1 单库 500MB**：只存元数据与切片 init（原始字节 BLOB），不存媒体正文。
5. **没有文档之外的新字段**：协议里多出来的只有 `/meta` 的 `storage` / `scheduler` 说明项，
   App 侧 `ignoreUnknownKeys` 会忽略。

## 测试

不依赖 Wrangler：`node:sqlite` 模拟 D1，本地桥接 + 桩存储模拟外部存储，真实 HTTP 桩站模拟源站/解析/切片。

```bash
cd backend-in-worker
node --test --test-concurrency=1 test/server.test.mjs test/media.test.mjs test/ingest.test.mjs test/upload.test.mjs test/budget.test.mjs
```

覆盖：协议主链路 17 例、媒体与存储上传 10 例、回调/鉴权/上传/清理 11 例、上传策略 6 例、子请求预算 3 例。

大文件用例单独跑（需要本机 nginx `:9200` 提供 `sample-200mb.mp4` / `sample-500mb.mp4`，
以及切片服务 `:3000`；不可达时自动跳过）：

```bash
node --test test/large-file.test.mjs
```

夹具是流式的：源站是 nginx，桩存储只保留 4MB 以内的字节供小夹具比对，
大文件只做流式 SHA-256、不保留完整字节，所以 500MB 也不会把测试进程撑大。
本机实测：200MB → 18 片、约 7s；500MB → 36 片、约 19s
（回环链路，不含真实外部存储的复制时间）。