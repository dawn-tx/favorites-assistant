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
│   ├── config.js            运行参数与默认值
│   ├── http.js              JSON 响应、请求体、鉴权、游标
│   ├── validate.js          字段校验
│   ├── hash.js              WebCrypto（SHA-256 / HMAC / base64）
│   ├── keys.js              虚拟 key、内容版本、内容指纹
│   ├── render.js            publicRow / primary + backup 渲染
│   ├── policy/
│   │   ├── index.js         汇总导出四块可替换策略
│   │   ├── parser.js        解析服务原始字段 → ResolvedMedia
│   │   ├── selection.js     backup 取一条可达 variant
│   │   ├── slicing.js       远程切片服务 + 单飞
│   │   └── storage.js       外部存储上传与下载
│   ├── probe.js             源探活（HEAD → Range 回退，带缓存）
│   ├── parse.js             解析编排：全部 variants → primary 段
│   ├── range.js             Range 头解析
│   ├── budget.js            子请求预算（免费版 50 次/调用）
│   ├── media/
│   │   └── proxy.js         媒体代理：primary variant / backup 存储 / 上传合成
│   ├── store/
│   │   ├── schema.sql       D1 建表
│   │   ├── mappers.js       D1 行 <-> 领域对象
│   │   ├── favorites.js     收藏读写与分页
│   │   ├── assets.js        资产与 variants 读写
│   │   └── jobs.js          任务队列（D1 + 锁）
│   ├── handlers/            sync / favorites / resolve / ingest / meta
│   └── scheduled/runner.js  解析、备份准备、上传、GC、删收藏
└── test/                    node:sqlite 模拟 D1、桥接 + 桩存储跑完整链路
```

## 外部存储怎么接

上传时按素材类型决定传给存储服务的地址，**只传 backup**：

- **backup 切片**：传本 Worker 的临时合成地址（`init + 源区间`），存储服务回源 Worker 拿到可直接播放的完整片；
- **短视频 backup / 备用图**：直接传源站地址，不再经过 Worker；
- **primary**（视频整片、图片、封面）永不上传；`BACKUP_COVERS=true` 时会额外生成一份封面 backup，仅供封面源失效时回落。

```
POST {STORAGE_UPLOAD_URL}
[{ "type": "video", "media": "https://<worker>/api/v1/media/<key>.mp4",
   "favorite": { "url": "https://www.bilibili.com/video/BV...", "summary": "解析服务返回的一句话说明" } },
 { "type": "image", "media": "https://source.example/backup.jpg",
   "favorite": { "url": "https://www.bilibili.com/video/BV...", "summary": "解析服务返回的一句话说明" } }]

→ ["id1", "id2"]        // 按上传顺序，单次最多 10 条
```

- 一批只属于**同一个收藏**，所以每条都带同一个 `favorite: { url, summary }`；`summary` 为空串时也会带上字段。
- backup 切片是独立文件：Worker 的虚拟地址就是 `init + 源区间` 合成后的完整片；
  短视频/备用图由存储服务直接回源源站。
- 直传源地址的条目不消耗 Worker 请求；只有 backup 切片回源 Worker 才计一次请求。
- 整批失败（任一条回源失败、非 2xx、id 数量不符）就按整条任务退避重试；
  收藏本身仍是 `ready`，失败只体现在 primary 的 `backup_state` / `backup_error`。
- 返回的 id 原样写进 `assets.store_id`；只有 backup 全部上传成功，primary 的 `backup_state` 才转 `ready`。
- **暂不处理重复**：同一文件重复上传会拿到新 id，以后接下载/删除时再按需要加本地索引。

`src/policy/storage.js` 的上传已接；下载按 `STORAGE_GET_URL` 取文件：

- `STORAGE_GET_URL` 支持 `{id}` 占位；没有占位时追加 `?id=<storeId>`，Range/HEAD 原样转发。
- 只有 backup 段走这个下载接口；primary 变体失败时先由后台换档并推新 URL，换不了才让 App 切 backup。
- `GET /media/full/<key>` 对 backup 同样忽略 `Range`，成功固定 `200`、`Accept-Ranges: none`。
- 删除目前仍是 no-op；删收藏/重新解析会在行删除之后调用它清理远端文件。

## 数据压缩（D1）

D1 只存元数据。业务库共 6 张表：

| 表 | 主键 / 唯一约束 | 关键字段 | 用途与生命周期 |
|---|---|---|---|
| `favorites` | `id`；`url` 唯一 | `shard/note/tags/source_app/favorite_at/updated_at/media/media_state/failed_reason/resolved_at/slice_rule/upload_*` | 一条收藏 url 一行；用户字段、内容状态与 backup 上传状态 |
| `sources` | `id`；`(url, etag, file_size)` 唯一 | `quality/created_at` | 源文件信息去重；被 `assets.source_id` 与 `variants.source_id` 引用；无引用后由 GC 回收 |
| `variants` | `key` 主键；每个 `(favorite_id, video_index)` 只有一条 `active` | `video_index/variant_index/source_id/quality/height/active` | 保存每个视频的全部清晰度；`key` 是该档独立虚拟 URL；换档只改 active |
| `assets` | `key` 主键 | `favorite_id/source_id/init_id/role/kind/video_index/slice_index/image_index/ord/title/duration_ms/cover_key/start_offset/media_size/store_id/backup_state` | primary 是视频整片、图片、封面；backup 是长视频切片、短视频整文件、备用图，`BACKUP_COVERS=true` 时还包括封面备份；backup 不进入顶层 `segments` |
| `inits` | `id`；`hash` 唯一 | `bytes BLOB/created_at` | backup 切片的 init 段按内容哈希共享，存原始字节；上传成功后 asset 清掉 `init_id/区间`，无引用后由 GC 删除 |
| `jobs` | `id`；`(url, kind)` 唯一 | `kind/attempts/next_at/locked_until` | 任务队列，`kind=parse/backup/upload`；完成后删除 |

逻辑关联（没有声明数据库外键）：

- `favorites.id` → `assets.favorite_id` / `variants.favorite_id`
- `sources.id` → `assets.source_id` / `variants.source_id`
- `inits.id` → `assets.init_id`（可空，backup 上传成功后清空）
- `jobs.url` → `favorites.url`

索引：`favorites_order`、`assets_by_favorite/source/init/cleanup`、`variants_by_favorite/source`、
`variants_one_active`（每个视频最多一条 active）、`jobs_once/jobs_ready`。

压缩口径：`favorites` 不存段 key 列表；primary 顺序由 `assets.ord` 决定；`sources` 与 `inits` 去重；
backup 切片上传后只保留 asset 行（约 0.2KB）。同一测试夹具（1 视频 2 片 + 1 图 + 1 封面）实测：
旧结构约 1.29KB，新结构约 0.69KB。

注意：真实切片每片的 `init` 通常不同（`moov` 会按片重写），`inits` 去重主要作用于重复解析与跨收藏复用。

### 分片部署（1 个控制库 + 9 个数据分片）

- `DB`：控制库，只存 `favorites` / `jobs`
- `DB_1..DB_9`：数据分片，只存 `assets` / `variants` / `sources` / `inits`
- 分片键：归一化收藏 URL 的稳定哈希 `% SHARD_COUNT`，写入 `favorites.shard`
- 媒体 key 带前缀 `s<shard>_<hash>`，代理按前缀直接选数据分片
- `list` / `lookup` 只查控制库取收藏行；再按 `favorites.shard` 批量补内容
- `jobs` 只在控制库，cron 仍然只有一个队列
- 没有 `DB_1` 时自动退回单库（测试/本地 dev 默认如此）

10 个库都执行同一份 `schema.sql`；数据分片里的 `favorites/jobs` 和控制库里的内容表保持空表。

## 部署（不在本地安装 Wrangler）

以下命令都在有 Wrangler 的环境执行（CI、Cloud Shell 或你自己的机器）：

```bash
# 1) 建 1 个控制库 + 9 个数据分片，把 id 填进 wrangler.toml
wrangler d1 create favorites
wrangler d1 create favorites-1
wrangler d1 create favorites-2
wrangler d1 create favorites-3
wrangler d1 create favorites-4
wrangler d1 create favorites-5
wrangler d1 create favorites-6
wrangler d1 create favorites-7
wrangler d1 create favorites-8
wrangler d1 create favorites-9

# 2) 每个库执行同一份 schema（新模型不迁移旧数据；旧库请新建或清空后执行）
for db in favorites favorites-1 favorites-2 favorites-3 favorites-4 favorites-5 favorites-6 favorites-7 favorites-8 favorites-9; do
  wrangler d1 execute "$db" --remote --file=src/store/schema.sql
done

# 3) secrets
wrangler secret put SYNC_TOKEN
wrangler secret put MEDIA_KEY_SECRET
wrangler secret put STORAGE_TOKEN

# 4) 在 wrangler.toml 里填：
#    SLICE_SERVICE_URL
#    MEDIA_BASE_URL      —— 部署后的 Worker 公网地址（含 /api/v1），存储服务要能访问
#    STORAGE_UPLOAD_URL  —— 外部存储的上传接口
#    解析服务地址/令牌/超时直接改 src/policy/parser.js 的 parserConfig

# 5) 部署
wrangler deploy
```

部署后先看两个免鉴权接口：

```bash
curl -s https://<worker>.workers.dev/api/v1/health
curl -s https://<worker>.workers.dev/api/v1/meta
```

## 配置说明

运行时配置大部分来自 `wrangler.toml` 的 `[vars]` 与 `wrangler secret`；`src/config.js` 在首次请求时解析并缓存。
解析器的地址、令牌与超时不再走 env，直接维护在 `src/policy/parser.js` 的 `parserConfig` 里；解析器可能不止一个，以后多解析器的选择逻辑也留在该模块。
数字类变量在 TOML 里写成字符串（如 `"300000"`），留空或非法时回落到默认值。
表里没有出现在 `wrangler.toml` 的变量，需要时直接加到 `[vars]` 即可覆盖默认值。
`/meta` 会回显大部分生效值（`next_wait_ms`、`slice_rule`、上传批大小、调度节奏等）。

### 绑定、入口与定时器

| 项 | 当前值 | 说明 |
|---|---|---|
| D1 绑定 `DB` | `database_name = "favorites"` | **控制库（必需）**：favorites / jobs；先执行 `schema.sql` |
| D1 绑定 `DB_1..DB_9` | `favorites-1..favorites-9` | 数据分片：assets / variants / sources / inits；每个库执行同一份 `schema.sql` |
| `SHARD_COUNT` | `9` | 逻辑数据分片数；没有 `DB_1` 时自动退回单库 |
| `main` | `src/index.js` | Worker 入口 |
| `compatibility_date` | `2025-09-01` | 运行时兼容日期 |
| `observability.enabled` | `true` | 打开 Workers Logs，便于看 `exceededCpu` |
| cron 1 | `*/3 * * * *` | 每 3 分钟跑任务队列（解析 / 备份准备 / 上传） |
| cron 2 | `41 4 * * *` | 每天回收无引用的 `inits` / `sources`（逐个数据分片） |

免费版每个账号最多 5 个 cron，这里用了 2 个；其余三个留给别的 Worker。

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
| `UPLOAD_WAIT_MS` | `5000` | `/sync`、`/resolve` 回执里的 `next_wait_ms` 与 `Retry-After` 基准；同时是 Worker 两次存储上传调用的最小间隔，也是存储服务 429 未给 `Retry-After` 时的退避值 |
| `UPLOAD_WAIT_JITTER_MS` | `0` | 大于 0 时按请求序号在 `0/1/2` 倍之间抖动；0 = 固定值 |

### 解析与切片

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| 解析服务 | 见 `policy/parser.js` 的 `parserConfig` | 地址、令牌、超时都是模块内常量，不走 env；空地址时每条解析直接失败 |
| `USER_AGENT` | Chrome 157 桌面版 | 探源（HEAD / Range）与调用解析服务时带上的 `User-Agent`；留空用 `src/config.js` 里的默认值 |
| `SLICE_SERVICE_URL` | 空 | 空 = 不启用切片；超过切片阈值的大视频会**解析失败**（不再退为整片），小视频仍整片。生产必须配置 |
| `SLICE_TIMEOUT_MS` | `60000` | 调切片服务的超时 |
| 默认切片规则（代码固定） | `{"mode":"size","bytes":18874368,"align":"keyframe","strategy":"auto","limit":20000000}` | 唯一默认值，**不再读 `SLICE_RULE` 环境变量**；也是「长视频」阈值。App 在请求里带 `slice_rule` 时只覆盖 `bytes`，其余字段保持这套默认 |
| `MAX_SLICES_PER_FAVORITE` | `50` | **单条收藏所有视频合计**最多切多少片；超出预算的视频退回整片 |
| `TRANSFER_MAX_ATTEMPTS` | `3` | 解析失败重试次数 |
| `TRANSFER_RETRY_MS` | `2000` | 解析重试退避基数（第 n 次等 `n × 该值`） |

`DEFAULT_SLICE_RULE` 只是默认值；App 在 `/sync`、`/resolve` 里带的规则会按条落库，优先级更高。规则只收按大小一种形状（`{mode:"size",bytes}`），切点固定关键帧；`bytes` 范围 64KiB–1GiB。视频选档不再设高度上限，按分辨率从高到低取第一个可达档。

### 媒体、密钥与切片缓存

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| `MEDIA_KEY_SECRET`（secret） | 空 | 虚拟 key 的 HMAC 密钥；空则用确定性 SHA-256。公网部署建议配置 |
| `SOURCE_PROBE_MS` | `5000` | 源站 HEAD/Range 探活超时 |
| `SOURCE_CHECK_TTL_MS` | `60000` | 探活结果缓存时长；0 = 每次都探（联调用） |

### 外部存储

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| `MEDIA_BASE_URL` | 空 | 切片上传时交给存储服务的 Worker 地址前缀，**必须包含 `/api/v1` 且公网可达**；只用于 backup 切片，短视频 backup/备用图/可选封面备份直传源地址 |
| `STORAGE_UPLOAD_URL` | 空 | 上传接口；空时上传失败（收藏仍 `ready`，只是没有备份） |
| `STORAGE_TOKEN`（secret） | 空 | 非空时上传接口带 Bearer |
| `STORAGE_GET_URL` | 空 | 下载接口：支持 `{id}` 占位，否则追加 `?id=<storeId>`；backup 播放只读这里 |
| `BACKUP_COVERS` | `false` | 是否额外保存视频封面 backup；开启后封面源失效时由代理回落，默认不保存 |
| `STORAGE_BATCH_SIZE` | `10` | 单次上传最多几条；代码硬顶 10 |
| `STORAGE_TIMEOUT_MS` | `300000`（5 分钟） | **单次批量上传调用**等存储「复制完成并返回 id」的超时；超时按整批失败退避 |

### 任务队列与备份

`jobs.kind` 共三类：

- `parse`：解析链接，写 primary 与全部 variants；
- `backup`：为每个视频/图片准备备用源；长视频切片、短视频整文件、图片单图；
- `upload`：把 backup 资产上传到外部存储，全部成功后才把 primary 的 `backup_state` 置 `ready`。

每日 GC 是独立 cron，不写 `jobs` 行。

| 变量 | 默认 | 作用 |
|---|---|---|
| `JOBS_PER_TICK` | `4` | 每 3 分钟分别最多领几条 `parse` / `backup` 任务；0 = 关闭解析（联调用） |
| `UPLOAD_JOBS_PER_TICK` | `2` | 每 3 分钟最多领几条 `upload` 任务；0 = 关闭上传 |
| `UPLOAD_ITEMS_PER_TICK` | `20` | 每条上传任务每轮最多处理几个 asset；按 `STORAGE_BATCH_SIZE` 切批 |
| `UPLOAD_JOB_LOCK_MS` | `600000`（10 分钟） | 上传任务锁；必须长于一次存储调用，否则下一轮会重复领 |
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
| 媒体请求子请求预算 | 10 | primary 探活 + 回源；backup 直接读存储 |
| 媒体成功响应缓存头 | `public, max-age=31536000, immutable` | 错误响应仍 `no-store` |
| 孤儿回收 | 每天 04:41 | 由 `wrangler.toml` 的 cron 决定 |

## 与 mock-server 的行为差异

| 方面 | mock-server | Worker 版 |
|---|---|---|
| 元数据 | `data.json` 单文件 | D1 `favorites` / `assets` / `jobs` |
| 媒体备份 | `store/` 本地文件 | 外部存储服务；只传 backup，primary 不进存储 |
| 后台上传 | 进程内上传器 | 备份准备 + 上传两个任务；backup 全成功才 ready |
| 后台任务 | 进程内单线程循环、`setInterval` | D1 任务队列 + 每 3 分钟 cron；写入后用 `waitUntil` 抢跑一次 |
| 切片信息 | `setInterval` 清理 | backup 上传成功后立即清掉 init/区间，不做 TTL 扫描 |
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

## 免费版额度与配置调节（重点）

### 哪些算额度，哪些不算

| 项 | 免费额度 | 说明 |
|---|---|---|
| Worker 请求 | **100,000 / 天** | 所有 HTTP 请求 + **每次 cron 触发** + WebSocket 建立连接；**WS 消息不计 Worker 请求**（消息在 DO 侧按 20:1 计费） |
| Worker CPU | **10 ms / 次** | 网络等待不计；流式转发大文件时，JS 拼流的开销会计入 |
| 子请求 | **50 / 次调用** | `fetch`、D1、KV、R2 都算；`db.batch()` 只算 1 次 D1 查询；媒体请求约 3 个 |
| D1 行读 | **5,000,000 / 天** | 清单页按返回行数、lookup 按命中的行、媒体按 1 行 |
| D1 行写 | **100,000 / 天** | `INSERT/UPDATE/DELETE` 都算；**索引也会各写一行** |
| D1 存储 | 单库 **500 MB** / 账号 **5 GB** | 只存元数据与 init，不存媒体正文 |
| DO 请求 | **100,000 / 天** | WS 连接建立 + 入站消息（20:1 折算）+ alarm |
| 外部存储 | 不占 Cloudflare 额度 | backup 切片回源 Worker 时算一次 Worker 请求；短视频 backup/备用图直传源站；封面默认不备份，`BACKUP_COVERS=true` 时额外一份 |
| 客户端缓存 | 不占任何服务端额度 | 只有第一次真正落到 Worker |

两个容易误解的点：

- **Workers 缓存命中仍按请求计费**（只省 CPU 和上游取数）。所以边缘缓存/Cache Rule 不能降低 10 万/天的请求数；要降请求数只能靠客户端缓存或让媒体直连存储。
- **cron 每次触发都是一次请求**。当前两条 cron 合计约 `480 + 1 = 481 次/天`（约 0.5%）。

### 一条收藏的账

设 `A = primary 段数`（视频 + 图片），`S = backup 切片数`，`B = backup 上传条数`（切片 + 短视频 + 备用图）：

| 动作 | 额度 |
|---|---|
| 首次播放（按 primary 预取） | 约 **A 次 Worker 请求**；播放器按 Range 请求时会更多 |
| 切到 backup | 每个备用项一次 Worker 请求（切片/整文件/图都读存储）；不切换就不产生 |
| 备份上传 | backup 切片约 **S 次 Worker 请求**（存储回源合成地址），短视频/备用图直传源站；另有 `ceil(B / STORAGE_BATCH_SIZE)` 次存储 API 调用 |
| D1 行写 | 约 `primary + variants + backup + 7` |
| 对账 | 清单档读 `total` 行；请求数 ≈ `ceil(total / 清单页大小)`（App 侧 300/页） |
| 解析 CPU | 随 variants 数量与探活次数增长；backup 切片在独立任务里做 |

50 片 × 18MB 的例子：首播约 50 次请求、备份约 50 次请求、D1 行写约 160；一天 1000 条这样的收藏就把 10 万请求吃满。

### 会影响额度的配置

| 配置 | 默认 | 影响 | 怎么调 |
|---|---|---|---|
| `MAX_SLICES_PER_FAVORITE` | `50` | 直接决定 A、备份请求、D1 行写、解析 CPU | 请求/CPU 吃紧就降到 20–30；视频会被更早退回整片 |
| App 的切片大小（`slice_rule.bytes`） | 18,874,368（18MiB） | 片越大 A 越少：18MB→36MB 约等于请求/行数减半 | 想省额度优先调大；代价是单次传输更大、seek 粒度更粗 |
| 默认切片规则（代码固定） | 18,874,368 字节 | 只影响没有随请求带规则的入口（B 回调等） | 有 App 请求时以 App 的规则为准；要改只能改代码里的 `DEFAULT_SLICE_RULE` |
| `UPLOAD_ITEMS_PER_TICK` | `20` | 每次 cron 最多处理多少 asset；**不改变每日 cron 次数** | 降下来更稳地守住 50 子请求/10ms；升上去只是缩短积压 |
| `UPLOAD_JOBS_PER_TICK` / `JOBS_PER_TICK` | `2` / `4` | 同上，控制单次调用的工作量 | 大批量收藏、经常 1102 时调小 |
| `STORAGE_BATCH_SIZE` | `10` | 存储 API 调用次数；**不会减少每个 asset 的回源请求** | 保持 10（接口上限） |
| `STORAGE_TIMEOUT_MS` | `5 分钟` | 太短会判失败并重试 → 重复存储调用 + 重复回源请求 | 片大时不要低于单批复制时间；保持小于上传锁 |
| `UPLOAD_JOB_LOCK_MS` | `10 分钟` | 太短会让下一轮 cron 重复领取同一任务 → 重复备份请求 | 必须大于一次存储调用耗时；更大片子调到 15 分钟 |
| `UPLOAD_MAX_ATTEMPTS` / `UPLOAD_RETRY_MS` | `3` / `2s` | 每次重试都重复存储调用 + 回源；失败通常是源站挂了，重试收益低 | 额度优先可降到 2 次；别为了“成功”无限加 |
| `TRANSFER_MAX_ATTEMPTS` / `TRANSFER_RETRY_MS` | `3` / `2s` | 每次重试重复解析服务 + 探活 + 切片 | 保持 3；解析服务不稳定时再加 |
| `SOURCE_CHECK_TTL_MS` | `60s` | primary 源探活缓存；过期就多一个子请求 | 调到 5 分钟可减少子请求/源站压力；不影响 Worker 请求数 |
| `UPLOAD_WAIT_MS` / `UPLOAD_WAIT_JITTER_MS` | `5000` / `0` | App 两条上传之间的节奏；同时是 Worker 调存储的间隔与 429 退避缺省值。不改变请求总数 | 只影响突发速率与限流退避，不影响每日额度 |
| cron `*/3 * * * *` | 每 3 分钟 | 480 次请求/天 | 改成 `*/10` 省约 336/天；写操作后的 `waitUntil` 仍会抢跑，代价是重试最坏多等几分钟 |
| cron `41 4 * * *` | 1 次/天 | 孤儿 init/source 回收 | 可降为每周一次，省得很少 |

### 推荐档位

| 目标 | backup 片大小 | `MAX_SLICES_PER_FAVORITE` | 每 tick | 探活 TTL | cron |
|---|---|---|---|---|---|
| **额度优先** | 32–64MB | 30 | 10 | 5 分钟 | `*/10 * * * *` |
| 平衡 | 18–24MB | 40 | 20 | 2 分钟 | `*/5 * * * *` |
| 体验优先 | 18MB | 50 | 20 | 60 秒 | `*/3 * * * *` |

### 不能只靠后端配置解决的部分

- **客户端缓存/预取**：A 个对象只请求一次、重复播放零请求；这是省请求数最大的一项，且不受后端配置影响。
- **媒体直连存储**：现在播放地址都要经过 Worker；302 只省 CPU，不省请求。要让媒体完全不占 Worker 请求，只能让 App 直接使用存储/CDN 的签名直链。
- **备份放大**：逐片备份是 A 次回源请求；整源备份一次（或懒备份）能把备份请求从 A 降到 1，但会改变存储形态/回落方式。
- **边缘缓存**：省 CPU 与上游，不省请求数（Workers 缓存命中仍计费）。

### 观察口径

- Cloudflare Dashboard 的 Workers Requests、D1 Row Metrics、DO Requests 是真实额度；
  `/health` 只报收藏/任务数，**不是额度计数器**。
- 先看 `/media`、`/sync`、cron 各占多少请求，再决定是调切片、降 cron，还是转向客户端缓存/存储直链。

## 已知边界

1. **backup 切片上传要求存储服务能访问 `MEDIA_BASE_URL`**：切片按临时合成地址回源 Worker；短视频 backup/备用图直连源站，不依赖它。
   虚拟地址里的不可猜 key 就是凭据，媒体接口本身免鉴权（播放器/Coil 不带 header）。
2. **删除还没接**：删收藏不会清远端 backup；主源失效时后台先尝试换档，换不了才让 App 切 backup。
3. **后台不是实时**：解析/备份准备最多滞后一个 cron 周期（180 秒）；写入接口会 `waitUntil` 抢跑一次，
   但免费版仍可能被 CPU 预算截断，没跑完的由 cron 接着跑。
4. **D1 单库 500MB**：只存元数据与 backup 切片的临时 init（原始字节 BLOB），不存媒体正文。
5. **backup 切片失败不再打垮 primary**：切片服务未配置、源站不支持 Range 或切片失败，只把该段 `backup_state` 记 `failed`，primary 仍 `ready`；
   `MAX_SLICES_PER_FAVORITE` 超限退回整文件 backup。
6. **没有文档之外的新字段**：协议里多出来的只有 `/meta` 的 `storage` / `scheduler` 说明项，
   App 侧 `ignoreUnknownKeys` 会忽略。

## 本地联调（不需要 Wrangler / D1 / 外部存储）

用于还没确定存储服务时，在平板上把 primary/backup 走通：

```bash
cd backend-in-worker
npm run dev:local
```

启动后：

- Worker 接口监听 `0.0.0.0:8787`，平板填 `http://<电脑局域网 IP>:8787/api/v1`
- D1 由本地 SQLite 文件 `dev/local.sqlite` 代替；默认单库，`SHARDS=2` 会额外建 `dev/local-1.sqlite` 等数据分片
- 删掉 `dev/local*.sqlite*` 重启即可重置
- 解析/切片/源站、外部存储 upload/get 都用本地桩；每 3 秒跑一次任务队列
- WS 中枢不可用，App 会自动退回 HTTP；真机 WS 要在真实 Worker 上测
- 夹具视频只有 200KB；用默认片大小通常不会切片。测试 backup 切片可先跑启动日志里那条 64KB `curl` 建一条，再在 App 里打开

Windows 平板要能访问 8787，先放行防火墙：

```powershell
netsh advfirewall firewall add rule name="favkit-local-worker" dir=in action=allow protocol=TCP localport=8787 profile=private
```

## 测试

不依赖 Wrangler：`node:sqlite` 模拟 D1，本地桥接 + 桩存储模拟外部存储，真实 HTTP 桩站模拟源站/解析/切片。

```bash
cd backend-in-worker
node --test --test-concurrency=1 test/*.test.mjs
```

覆盖：协议主链路、primary/变体/换档、backup 准备与 WS、只读存储、上传策略、回调/鉴权、预算、WebSocket、双分片路由等；当前共 67 例（65 pass / 2 个大文件用例视环境跳过）。

大文件用例单独跑（需要本机 nginx `:9200` 提供 `sample-200mb.mp4` / `sample-500mb.mp4`，
以及切片服务 `:3000`；不可达时自动跳过）：

```bash
node --test test/large-file.test.mjs
```

夹具是流式的：源站是 nginx，桩存储只保留 4MB 以内的字节供小夹具比对，
大文件只做流式 SHA-256、不保留完整字节，所以 500MB 也不会把测试进程撑大。
本机实测：200MB → 18 片、约 7s；500MB → 36 片、约 19s
（回环链路，不含真实外部存储的复制时间）。