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
init 原始字节约 8.3KB/片，热态约 8.5KB/片（20 片约 170KB、36 片约 300KB、50 片约 430KB）；
切片信息清理后每片只剩 asset 行（约 0.2KB），D1 占用会明显下降。

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

## 配置说明

运行时配置全部来自 `wrangler.toml` 的 `[vars]` 与 `wrangler secret`；`src/config.js` 在首次请求时解析并缓存。
数字类变量在 TOML 里写成字符串（如 `"300000"`），留空或非法时回落到默认值。
表里没有出现在 `wrangler.toml` 的变量，需要时直接加到 `[vars]` 即可覆盖默认值。
`/meta` 会回显大部分生效值（`next_wait_ms`、`slice_rule`、上传批大小、调度节奏等）。

### 绑定、入口与定时器

| 项 | 当前值 | 说明 |
|---|---|---|
| D1 绑定 `DB` | `database_name = "favorites"` | **必需**；先执行 `src/store/schema.sql`。没有 KV / R2 绑定 |
| `main` | `src/index.js` | Worker 入口 |
| `compatibility_date` | `2025-09-01` | 运行时兼容日期 |
| `observability.enabled` | `true` | 打开 Workers Logs，便于看 `exceededCpu` |
| cron 1 | `* * * * *` | 每分钟跑任务队列（解析 + 备份） |
| cron 2 | `23 * * * *` | 每小时清理空闲切片信息 |
| cron 3 | `41 4 * * *` | 每天回收无引用的 `inits` / `sources` |

免费版每个账号最多 5 个 cron，这里用了 3 个；其余两个留给别的 Worker。

### 路由与鉴权

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| `BASE_PATH` | `/api/v1` | 所有数据接口与媒体地址的前缀，必须与 App 设置里的接口地址后缀一致 |
| `SYNC_TOKEN`（secret） | 空 | 空 = 数据接口不校验；非空则要求 `Authorization: Bearer <token>`。`/health`、`/meta`、`/media` 永远免鉴权 |
| `INGEST_PATH` | `/ingest/callback` | B 回调路径；完整路径是 `${BASE_PATH}${INGEST_PATH}`，免鉴权 |
| `INGEST_SOURCE_APP` | `第三方` | 回调进来的条目的 `source_app`，载荷里带什么都不认 |

### 客户端节奏

| 变量 | 默认 | 作用 |
|---|---|---|
| `UPLOAD_WAIT_MS` | `3000` | `/sync`、`/resolve` 回执里的 `next_wait_ms` 与 `Retry-After` 基准 |
| `UPLOAD_WAIT_JITTER_MS` | `0` | 大于 0 时按请求序号在 `0/1/2` 倍之间抖动；0 = 固定值 |

### 解析与切片

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| `PARSE_SERVICE_URL` | 空 | **生产必须配置**；空时每条解析直接失败 |
| `PARSE_SERVICE_TOKEN`（secret） | 空 | 非空时给解析服务带 Bearer |
| `PARSE_TIMEOUT_MS` | `30000` | 调解析服务的超时 |
| `SLICE_SERVICE_URL` | 空 | 空 = 不切片（长视频整片一段） |
| `SLICE_TIMEOUT_MS` | `60000` | 调切片服务的超时 |
| `SLICE_RULE` | `{"mode":"size","bytes":2000000,"align":"keyframe"}` | 没有随请求带规则时的默认片大小；也是「长视频」阈值。只收按大小或按时长两种形状，切点固定关键帧；按大小时 64KiB–1GiB |
| `UPLOAD_MAX_HEIGHT` | `1080` | 每个视频选一档：高度不超过它的最高可达档；没有则取最小档 |
| `MAX_SLICES_PER_FAVORITE` | `50` | **单条收藏所有视频合计**最多切多少片；超出预算的视频退回整片 |
| `TRANSFER_MAX_ATTEMPTS` | `3` | 解析失败重试次数 |
| `TRANSFER_RETRY_MS` | `2000` | 解析重试退避基数（第 n 次等 `n × 该值`） |

`SLICE_RULE` 只是默认值；App 在 `/sync`、`/resolve` 里带的规则会按条落库，优先级更高。

### 媒体、密钥与切片缓存

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| `MEDIA_KEY_SECRET`（secret） | 空 | 虚拟 key 的 HMAC 密钥；空则用确定性 SHA-256。公网部署建议配置 |
| `SOURCE_PROBE_MS` | `2500` | 源站 HEAD/Range 探活超时 |
| `SOURCE_CHECK_TTL_MS` | `60000` | 探活结果缓存时长；0 = 每次都探（联调用） |
| `SLICE_INFO_TTL_MS` | `2592000000`（30 天） | 切片 init/区间作为派生缓存的空闲上限；只有已有备份的片才会被清 |
| `SLICE_INFO_TOUCH_MS` | `600000`（10 分钟） | 媒体请求更新 `last_used_at` 的合并间隔，避免每个 Range 都写 D1 |

### 外部存储

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| `MEDIA_BASE_URL` | 空 | 交给存储服务的虚拟地址前缀，**必须包含 `/api/v1` 且公网可达**；空时上传直接失败 |
| `STORAGE_UPLOAD_URL` | 空 | 上传接口；空时上传失败（收藏仍 `ready`，只是没有备份） |
| `STORAGE_TOKEN`（secret） | 空 | 非空时上传接口带 Bearer |
| `STORAGE_GET_URL` | 空 | **预留**：给下载实现用；当前媒体回落还没接，读取但不生效 |
| `STORAGE_BATCH_SIZE` | `10` | 单次上传最多几条；代码硬顶 10 |
| `STORAGE_TIMEOUT_MS` | `300000`（5 分钟） | **单次批量上传调用**等存储「复制完成并返回 id」的超时；超时按整批失败退避 |

### 任务队列与备份

| 变量 | 默认 | 作用 |
|---|---|---|
| `JOBS_PER_TICK` | `4` | 每分钟最多领几条解析任务；0 = 关闭解析（联调用） |
| `UPLOAD_JOBS_PER_TICK` | `2` | 每分钟最多领几条上传任务；0 = 关闭上传 |
| `UPLOAD_ITEMS_PER_TICK` | `20` | 每条上传任务每轮最多处理几个 asset；按 `STORAGE_BATCH_SIZE` 切批 |
| `UPLOAD_JOB_LOCK_MS` | `600000`（10 分钟） | 上传任务锁；必须长于一次存储调用，否则下一分钟会重复领 |
| `UPLOAD_MAX_ATTEMPTS` | `3` | 整条上传任务的重试次数（真失败才计次，预算用尽不计） |
| `UPLOAD_RETRY_MS` | `2000` | 上传重试退避基数 |

三者关系：`STORAGE_TIMEOUT_MS`（5 分钟）< `UPLOAD_JOB_LOCK_MS`（10 分钟）< cron 单次墙钟（15 分钟）。

### 固定值（不能通过 env 改）

| 项 | 值 | 说明 |
|---|---|---|
| 解析任务锁 | 90 秒 | 防止两个 tick 同时解析同一条 |
| 请求体上限 | 4MiB | 超出回 413 |
| `/favorites/lookup` 上限 | 200 个 url | 超出回 400 |
| 单次上传条数硬顶 | 10 | 即使把 `STORAGE_BATCH_SIZE` 配大也会被夹到 10 |
| 调度器子请求预算 | 45 | 每轮留出余量给主请求；见 `src/budget.js` |
| 媒体请求子请求预算 | 10 | 探活 + 补切片 + 回源 |
| 媒体成功响应缓存头 | `public, max-age=31536000, immutable` | 错误响应仍 `no-store` |
| 切片清理 / 孤儿回收 | 每小时 :23 / 每天 04:41 | 由 `wrangler.toml` 的 cron 决定 |

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

## WebSocket 控制面

除 HTTP 外，开了一条 `GET /api/v1/ws`（需 `Authorization: Bearer`，`https` 对应 `wss`）：

- 连接进 Durable Object `LibraryHub`（SQLite 版，免费版可用；用它做多连接广播）。
- App 可在同一连接上发 `hello / list / lookup / resolve / ping`，请求带 `id`，响应同 `id`。
- 服务端在收藏变化时推送：
  - `{ "t": "favorites", "favorites": [完整收藏行...] }`（解析完成、备注变更、ingest 新增）；
  - `{ "t": "deleted", "urls": [...] }`。
  每 20 条收藏 / 100 个 url 一帧。
- 推送是增强：WS 断开或未配置 `LIB` 绑定都不影响 HTTP 接口；App 断线重连后跑一次对账补齐。
- 上传仍走 `POST /sync`，媒体仍走 HTTP；WS 只承载控制面。
- DO 免费额度：10 万请求/天、13,000 GB-s/天；WS 入站消息按 20:1 计费，出站与协议 ping 不计费，
  连接用 Hibernation API，空闲不烧时长。

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
  按 18MB/片、真实 init 约 8.3KB 估，单条收藏热态约 170KB（20 片）到 430KB（50 片）；
  免费版单库 500MB 大约放 1200–3000 条热态长视频，切片信息清理后可到几万条。
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

覆盖：协议主链路 17 例、媒体与存储上传 10 例、回调/鉴权/上传/清理 11 例、上传策略 6 例、子请求预算 3 例、WebSocket 中枢 6 例。

大文件用例单独跑（需要本机 nginx `:9200` 提供 `sample-200mb.mp4` / `sample-500mb.mp4`，
以及切片服务 `:3000`；不可达时自动跳过）：

```bash
node --test test/large-file.test.mjs
```

夹具是流式的：源站是 nginx，桩存储只保留 4MB 以内的字节供小夹具比对，
大文件只做流式 SHA-256、不保留完整字节，所以 500MB 也不会把测试进程撑大。
本机实测：200MB → 18 片、约 7s；500MB → 36 片、约 19s
（回环链路，不含真实外部存储的复制时间）。