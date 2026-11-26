# 收藏助手后台 · Cloudflare Worker 版

与 [mock-server](../mock-server/server.mjs) 共用一套 HTTP 协议（见 [同步后台接口说明](../docs/同步后台接口说明.md)）。
Worker 版没有常驻进程和本地磁盘：元数据落 D1，媒体副本交给外部存储，后台任务由 cron 驱动。

App 只需要把接口地址指向 Worker 的 `/api/v1`。生产建议使用自定义域名，例如：

- `api.example.com`：App / 媒体
- `admin.example.com`：管理台，Cloudflare Access 保护

## 1. 目录

```text
backend-in-worker/
├── wrangler.toml            D1、cron、vars、assets、per-entrypoint cache
├── pages/                   Vue 3 + Vite 管理台（构建到 pages/dist）
├── src/
│   ├── entry.js             Worker 导出：default + LibraryHub + MediaCache
│   ├── index.js             默认入口：路由分发 + cron
│   ├── media-cache.js       只处理 c_ 标记的 Workers Cache entrypoint
│   ├── config.js            运行参数与默认值
│   ├── http.js              JSON 响应、请求体、鉴权、游标
│   ├── validate.js          字段校验
│   ├── hash.js              WebCrypto
│   ├── keys.js              key、普通/缓存媒体路径、内容版本
│   ├── render.js            publicRow / primary + backup 渲染
│   ├── policy/              parser、selection、slicing、storage 适配
│   ├── probe.js             源探活
│   ├── parse.js             解析编排
│   ├── budget.js            子请求预算
│   ├── media/proxy.js       媒体代理、上传合成、缓存守卫、CORS
│   ├── store/               D1 schema、mappers、favorites、assets、jobs
│   ├── handlers/            sync / favorites / resolve / ingest / meta / admin
│   └── scheduled/runner.js  解析、备份、上传、GC、删除
├── test/                    node:sqlite + 本地桥接 + 桩存储
└── pages/README.md          管理台开发说明
```

## 2. 核心模型

### 2.1 primary 和 backup

- **primary**：源站返回的视频/图片。来源是解析服务保存的 variants，每个视频只有一条 active，primary 始终是完整文件。
- **backup**：上传到外部存储的副本。视频是否切片由 `slice_rule.bytes` / `DEFAULT_SLICE_RULE` 阈值决定：
  - 源文件超过切片阈值：拆成多个独立 mp4 切片；
  - 未超过：一个整文件；
  - 图片：单图；
  - `BACKUP_COVERS=true`：额外保存视频封面备份。
- **切片阈值只决定 backup 形态**，与缓存阈值 `PRIMARY_CACHE_MAX_BYTES` 是两件事。

解析成功即可播放 primary；backup 由独立任务异步准备和上传。全部 backup 成功后才把 primary 的 `backup_state` 置 `ready`。

### 2.2 App 只有一种媒体路径

App 始终请求：

```text
/media/<key>.<ext>
```

后台在 key 前加内部标记，不新增第二个公开接口：

- `c_`：进入 `MediaCache` entrypoint，使用 Workers Cache；
- `n_`：进入默认入口，走普通 Range 代理，不缓存。

同一个素材的 URL 在缓存命中前后不变。App 不需要识别标记，也不需要切换请求。

`GET /media/full/<key>.<ext>` 是普通整段入口：忽略 Range，成功固定 `200` 且 `accept-ranges: none`；它不进入 `c_` 缓存入口。

### 2.3 缓存规则

| 媒体 | 缓存 |
|---|---|
| 所有 backup（切片、整文件、备用图、可选封面） | 全部缓存 |
| primary 图片/封面 | 缓存 |
| primary 视频，源文件大小 ≤ `PRIMARY_CACHE_MAX_BYTES` | 缓存 |
| primary 视频，超过阈值或大小未知 | 不缓存，Range 代理 |

`PRIMARY_CACHE_MAX_BYTES` 默认 `18,874,368` 字节；设为 `0` 表示不缓存 primary 视频。

`MediaCache` 入口会再次检查 asset 类型，手动给超过阈值的 primary 视频拼 `c_` 也会返回 404。

完整缓存说明、Range 行为和管理台探测见 [Cloudflare 媒体缓存](../docs/Cloudflare媒体缓存配置.md)。

### 2.4 上传与下载

默认所有 backup 的上传地址都走 Worker：

```text
MEDIA_BASE_URL + /media/c_<key>.<ext>
```

- 切片由 Worker 把 `init + 源区间` 合成完整片；
- 整文件/图片/封面由 Worker 从源站流式代理完整文件；
- 是否切片仍由切片阈值决定；
- 上传前后媒体 URL 不变；
- `STORAGE_MEDIA_VIA_WORKER=false` 时，只有切片走 Worker，整文件/图片/封面直传源站。

存储上传契约：

```json
[
  {
    "type": "video",
    "media": "https://<worker>/api/v1/media/c_<key>.mp4",
    "favorite": {
      "url": "https://www.bilibili.com/video/BV...",
      "summary": "解析服务返回的一句话说明"
    }
  }
]
```

- 一批只属于同一个收藏，最多 10 条；
- 返回与入参顺序一致的 id 数组；
- id 写入 `assets.store_id`；
- 任一条失败则整批按任务退避重试，primary 仍是 `ready`，失败体现在 `backup_state` / `backup_error`。

`STORAGE_GET_URL` 用于下载 backup：

- 支持 `{id}` 占位；没有占位时追加 `?id=<storeId>`；
- Range/HEAD 原样转发；
- 429 原样返回给客户端，并带 `Retry-After`；
- 删除目前仍是 no-op。

## 3. D1 数据模型

D1 只存元数据，不存媒体正文。业务表 6 张：

| 表 | 主键 / 唯一约束 | 关键字段 | 用途 |
|---|---|---|---|
| `favorites` | `id`；`url` 唯一 | `shard/note/tags/source_app/favorite_at/updated_at/media/media_state/failed_reason/resolved_at/slice_rule/upload_*` | 一条收藏一行；用户字段、内容状态、backup 上传状态 |
| `sources` | `id`；`(url, etag, file_size)` 唯一 | `quality/created_at` | 源文件信息去重；无引用后 GC |
| `variants` | `key` 主键；每个 `(favorite_id, video_index)` 最多一条 `active` | `video_index/variant_index/source_id/quality/height/active` | 保存每个视频的全部清晰度；换档只改 active |
| `assets` | `key` 主键 | `favorite_id/source_id/init_id/role/kind/video_index/slice_index/image_index/ord/title/duration_ms/cover_key/start_offset/media_size/store_id/backup_state` | primary 是源站整片/图片/封面；backup 是存储副本 |
| `inits` | `id`；`hash` 唯一 | `bytes BLOB/created_at` | 切片 init 按内容哈希共享；上传成功后清掉引用，无引用后 GC |
| `jobs` | `id`；`(url, kind)` 唯一 | `kind/attempts/next_at/locked_until` | 任务队列：parse / backup / upload |

逻辑关联没有数据库外键：

- `favorites.id` → `assets.favorite_id` / `variants.favorite_id`
- `sources.id` → `assets.source_id` / `variants.source_id`
- `inits.id` → `assets.init_id`（可空，backup 上传成功后清空）
- `jobs.url` → `favorites.url`

`favorites` 不存段 key 列表；primary 顺序由 `assets.ord` 决定。`sources` 与 `inits` 去重；backup 切片上传成功后只保留 asset 行（约 0.2KB），并清掉 init/区间。真实切片每片 init 通常不同，`inits` 去重主要作用于重复解析与跨收藏复用。

### 分片

- `DB`：控制库，只存 `favorites` / `jobs`
- `DB_1..DB_9`：数据分片，只存 `assets` / `variants` / `sources` / `inits`
- 分片键：归一化 URL 的稳定哈希 `% SHARD_COUNT`，写入 `favorites.shard`
- 媒体 key 前缀：`s<shard>_<hash>`
- 列表/lookup 只查控制库，再按 `shard` 批量补内容
- 没有 `DB_1` 时自动退回单库（本地测试默认）

10 个库执行同一份 [schema.sql](src/store/schema.sql)；数据分片里的 `favorites/jobs` 和控制库里的内容表保持空表。

### 3.1 WebSocket 控制面

`GET /api/v1/ws`（https 对应 wss；配置了 `SYNC_TOKEN` 时要求 Bearer）连接到 Durable Object `LibraryHub`：

- 客户端可发 `hello / list / lookup / resolve / ping`，请求带 `id`，响应同 `id`；
- 服务端推送：
  - `{ "t": "favorites", "favorites": [完整收藏行] }`
  - `{ "t": "deleted", "urls": [...] }`
- 每 20 条收藏 / 100 个 url 一帧；
- WS 是增强，断开不影响 HTTP；App 重连后跑一次对账；
- 上传仍走 HTTP `/sync`，媒体仍走 HTTP；WS 只承载控制面。

DO 免费额度约 10 万请求/天；WS 入站消息按 20:1 计费，出站和协议 ping 不计费，使用 Hibernation API 空闲不烧时长。

## 4. 配置

配置来自 `wrangler.toml` 的 `[vars]` 与 `wrangler secret`，`src/config.js` 首次请求时解析并缓存。数字变量在 TOML 中写成字符串；空值/非法值回落默认。

### 4.1 绑定与入口

| 项 | 当前值 | 说明 |
|---|---|---|
| `main` | `src/entry.js` | default + `LibraryHub` + `MediaCache` |
| `DB` | `favorites` | 控制库；执行 schema |
| `DB_1..DB_9` | `favorites-1..9` | 数据分片；执行同一份 schema |
| `SHARD_COUNT` | `9` | 无 `DB_1` 时自动单库 |
| `compatibility_date` | `2025-09-01` | 运行时兼容日期 |
| `observability.enabled` | `true` | Workers Logs |
| cron 1 | `*/3 * * * *` | 解析/备份/上传 |
| cron 2 | `41 4 * * *` | 回收无引用 `inits` / `sources` |

### 4.2 路由与鉴权

| 变量 | 默认 | 作用 |
|---|---|---|
| `BASE_PATH` | `/api/v1` | App 数据接口和媒体路径前缀 |
| `ADMIN_HOST` | 空 | 管理台子域；非空时 `/admin/api/*` 只在该 Host 响应。生产必须设置 |
| `SYNC_TOKEN`（secret） | 空 | 非空时数据接口要求 Bearer；`/health`、`/meta`、`/media` 免鉴权 |
| `INGEST_PATH` | `/ingest/callback` | B 回调路径；完整路径 `${BASE_PATH}${INGEST_PATH}` |
| `INGEST_SOURCE_APP` | `第三方` | 回调条目固定写这个来源 |

`GET /health` 和 `GET /meta` 免鉴权；`/health` 报收藏/任务计数，`/meta` 报游标、媒体路径、缓存阈值、存储和调度等有效配置。

### 4.3 解析与切片

| 变量 | 默认 | 作用 |
|---|---|---|
| 解析服务 | `src/policy/parser.js` 的 `parserConfig` | 地址/令牌/超时是模块内常量，不走 env |
| `USER_AGENT` | Chrome 157 桌面版 | 探源与调用解析服务时的 UA |
| `SLICE_SERVICE_URL` | 空 | 空 = 不启用切片；超过切片阈值的 backup 会失败，未超过仍整片。生产必须配置 |
| `SLICE_TIMEOUT_MS` | `60000` | 调切片服务超时 |
| `DEFAULT_SLICE_RULE` | 代码固定：18MiB / keyframe / auto / 20MB limit | 没有随请求带规则时的 backup 切片阈值 |
| `MAX_SLICES_PER_FAVORITE` | `50` | 单条收藏所有视频合计最多切多少片；超出退回整文件 |
| `TRANSFER_MAX_ATTEMPTS` | `3` | 解析失败重试次数 |
| `TRANSFER_RETRY_MS` | `2000` | 解析重试退避基数 |

`DEFAULT_SLICE_RULE` 只影响 backup 形态；App 在 `/sync`、`/resolve` 带的 `slice_rule` 按条落库，优先于默认值。规则只收 `{mode:"size",bytes}`，`bytes` 范围 64KiB–1GiB。

### 4.4 媒体、缓存与密钥

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| `MEDIA_KEY_SECRET`（secret） | 空 | 媒体 key 的 HMAC 密钥；公网部署建议配置 |
| `SOURCE_PROBE_MS` | `5000` | 源站 HEAD/Range 探活超时 |
| `SOURCE_CHECK_TTL_MS` | `60000` | 探活结果缓存时长；0 = 每次都探 |
| `PRIMARY_CACHE_MAX_BYTES` | `18874368` | primary 视频是否缓存的阈值；0 = 不缓存 primary 视频；backup 不受影响 |

`wrangler.toml` 使用 per-entrypoint 缓存：默认入口 `enabled=false`，只有 `[exports.MediaCache.cache] enabled=true`；`cross_version_cache=true`，避免每次部署清空缓存。

### 4.5 外部存储

| 变量 / secret | 默认 | 作用 |
|---|---|---|
| `STORAGE_MEDIA_VIA_WORKER` | `true` | 所有 backup 上传地址走 Worker；false 时只有切片走 Worker，其余直传源站 |
| `MEDIA_BASE_URL` | 空 | Worker 公网地址前缀，**必须含 `/api/v1` 且存储服务可访问** |
| `STORAGE_UPLOAD_URL` | 空 | 外部存储上传接口 |
| `STORAGE_TOKEN`（secret） | 空 | 非空时上传接口带 Bearer |
| `STORAGE_GET_URL` | 空 | 下载接口；支持 `{id}` 或 `?id=<storeId>` |
| `BACKUP_COVERS` | `false` | 是否额外保存视频封面 backup |
| `STORAGE_BATCH_SIZE` | `10` | 单次上传最多几条；代码硬顶 10 |
| `STORAGE_TIMEOUT_MS` | `300000` | 单次批量上传等「复制完成并返回 id」的超时 |

### 4.6 任务队列

| 变量 | 默认 | 作用 |
|---|---|---|
| `JOBS_PER_TICK` | `4` | 每轮 parse / backup 各最多领几条；0 = 关闭 |
| `UPLOAD_JOBS_PER_TICK` | `2` | 每轮最多领几条 upload；0 = 关闭 |
| `UPLOAD_ITEMS_PER_TICK` | `20` | 每条 upload 每轮最多处理几个 asset |
| `UPLOAD_JOB_LOCK_MS` | `600000` | 上传任务锁；必须长于一次存储调用 |
| `UPLOAD_MAX_ATTEMPTS` | `3` | 整条上传任务重试次数 |
| `UPLOAD_RETRY_MS` | `2000` | 上传重试退避基数 |

`STORAGE_TIMEOUT_MS`（5 分钟）< `UPLOAD_JOB_LOCK_MS`（10 分钟）< cron 单次墙钟（15 分钟）。

### 4.7 固定值

| 项 | 值 |
|---|---|
| 解析任务锁 | 90 秒 |
| 请求体上限 | 4MiB |
| `/favorites/lookup` 上限 | 200 个 url |
| 单次上传硬顶 | 10 条 |
| 调度器子请求预算 | 45 |
| 媒体请求子请求预算 | 10 |
| 媒体成功缓存头 | `public, max-age=31536000, immutable` |
| 媒体错误缓存头 | `no-store` |
| 孤儿回收 | 每天 04:41 |

## 5. 管理台

`pages/` 是独立 Vue 3 + Vite 管理台，不进入 App：

- 构建到 `pages/dist`，由 Worker 的 `[assets]` 提供；
- `[assets]` 使用 assets-first，`run_worker_first` 只匹配 `/admin/api/*`、`/api/*`、`/media/*`；静态资源不进入 Worker Cache；
- `admin.example.com` 绑到同一 Worker，并套 Cloudflare Access；
- `ADMIN_HOST=admin.example.com`；
- 只读 API：`/admin/api/overview`、`/admin/api/favorites`、`/admin/api/jobs`；
- 媒体详情“检查/预热”发 `HEAD` 读取 `cf-cache-status` / `age`；冷缓存可能触发完整对象填充；
- 本地开发：`npm run dev:admin`；构建：`npm run build:admin`。

管理台详细说明见 [pages/README.md](pages/README.md)。

## 6. 部署（GitHub 直连，不需要本地 Wrangler）

推荐用 Cloudflare Workers Builds 从 GitHub 部署。云端会执行 Build command 和 Deploy command，本地不需要安装 Wrangler。

### 6.1 先建 Cloudflare 资源

Worker 本身由 GitHub 部署，不需要本地 Wrangler。下面只是**一次性初始化**：可以在 Cloudflare Dashboard 创建 D1、执行 schema、设置 secrets；也可以临时用 CLI/CI 执行，不参与日常部署。

```bash
# 一次性初始化；也可在 Dashboard 的 D1 页面逐个创建
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
```

把返回的 `database_id` 填进 [wrangler.toml](wrangler.toml) 的 `DB` 和 `DB_1..DB_9`。

```bash
# 每个库执行同一份 schema；也可以在 Dashboard 的 D1 console 逐个粘贴 schema.sql
for db in favorites favorites-1 favorites-2 favorites-3 favorites-4 favorites-5 favorites-6 favorites-7 favorites-8 favorites-9; do
  wrangler d1 execute "$db" --remote --file=src/store/schema.sql
done
```

### 6.2 配置 secrets

在 Dashboard 的 Worker → Settings → Variables & Secrets 添加；或在有 Wrangler 的一次性环境执行：

```bash
wrangler secret put SYNC_TOKEN
wrangler secret put MEDIA_KEY_SECRET
wrangler secret put STORAGE_TOKEN
```

### 6.3 填写 vars

至少确认：

```toml
ADMIN_HOST = "admin.example.com"
MEDIA_BASE_URL = "https://api.example.com/api/v1"
STORAGE_UPLOAD_URL = "https://<storage>/upload"
STORAGE_GET_URL = "https://<storage>/get"
SLICE_SERVICE_URL = "https://<slice-service>/..."
```

其他可选：

```toml
STORAGE_MEDIA_VIA_WORKER = "true"
PRIMARY_CACHE_MAX_BYTES = "18874368"
BACKUP_COVERS = "false"
```

解析服务地址/令牌/超时改 `src/policy/parser.js` 的 `parserConfig`，不走环境变量。

### 6.4 Cloudflare 项目和域名

在 Cloudflare Dashboard：

1. 新建 Worker 项目并连接 GitHub 仓库。
2. **Root directory**：`backend-in-worker`
3. **Build command**：`npm run build:admin`
4. **Deploy command**：`npm run deploy`（或 `npx wrangler deploy`）
5. 给 Worker 绑定两个自定义域名：
   - `api.example.com`：App 和媒体；
   - `admin.example.com`：管理台。
6. 在 Zero Trust / Access 给 `admin.example.com` 建应用，只允许自己的邮箱/身份。
   具体路径：Zero Trust → Access → Applications → Add a self-hosted application；domain 填 `admin.example.com`，policy 用 email/OTP。
7. `ADMIN_HOST` 填 `admin.example.com`。
8. 存储服务必须能访问 `MEDIA_BASE_URL`。

`pages/dist` 会由 Build command 生成，并作为静态 assets 随 Worker 一起部署。`backend-in-worker/package.json` 已声明 `wrangler ^4.107.0`；`pages/` 是独立的 package。

### 6.5 部署后验证

```bash
curl -s https://api.example.com/api/v1/health
curl -s https://api.example.com/api/v1/meta
```

- 看 `meta.primary_cache_max_bytes`、`meta.media_internal_markers`、存储配置；
- 新建一条收藏，确认 App 能拿到 `/media/c_...` 或 `/media/n_...`；
- 查看存储日志：所有 backup 的 `media` 都应该指向 Worker 的 `c_` 地址；
- 连续请求一个 backup 的 `c_` 地址，观察 `cf-cache-status`；
- 打开 `https://admin.example.com`，确认 Access 生效、概览/收藏/jobs 有数据。

### 6.6 回滚

- 存储上传回退：`STORAGE_MEDIA_VIA_WORKER=false` 后重新部署；
- 关闭缓存：把 `wrangler.toml` 的 `[exports.MediaCache.cache] enabled` 改为 `false` 后部署；
- 管理台只读接口不需要额外回滚，删除 Access 应用即可阻止访问。

## 7. 额度与调节

免费版主要额度：

| 项 | 免费额度 |
|---|---|
| Worker 请求 | 100,000 / 天 |
| Worker CPU | 10 ms / 次 |
| 子请求 | 50 / 次调用 |
| D1 行读 | 5,000,000 / 天 |
| D1 行写 | 100,000 / 天 |
| D1 存储 | 500 MB / 库；5 GB / 账号 |
| DO 请求 | 100,000 / 天 |
| cron | 每条 cron 触发都算一次请求 |

当前 cron 约 `480 + 1 = 481 次/天`。Workers Cache 命中仍计一次 Worker 请求，但不执行 Worker、不计 CPU；它主要省 D1、探活、存储/源站回源，不省每日请求额度。

主要调节项：

| 配置 | 影响 |
|---|---|
| `MAX_SLICES_PER_FAVORITE` | 降低会减少 backup 请求、D1 行写和解析 CPU |
| `slice_rule.bytes` | 调大减少 backup 片数，代价是单次传输更大、seek 粒度更粗 |
| `PRIMARY_CACHE_MAX_BYTES` | 只影响 primary 视频是否缓存，不影响 backup |
| `UPLOAD_ITEMS_PER_TICK` / `JOBS_PER_TICK` | 降低可更稳地守住 50 子请求/10ms |
| `STORAGE_BATCH_SIZE` | 只影响存储 API 调用次数，不减少每个 asset 的回源 |
| `STORAGE_MEDIA_VIA_WORKER` | false 可减少整文件/图片/封面的 Worker 上传请求，但不再统一走 Worker |
| cron 周期 | 拉长可省请求数，代价是任务延迟变长 |

App 本地缓存是省请求数最大的一层；Workers Cache 不是请求额度优化。

## 8. 本地联调

不需要 Wrangler/D1/外部存储：

```bash
cd backend-in-worker
npm run dev:local
```

- Worker 监听 `0.0.0.0:8787`，App 填 `http://<电脑局域网 IP>:8787/api/v1`；
- D1 由 `dev/local.sqlite` 代替；`SHARDS=2` 会创建数据分片；
- 解析/切片/源站/存储 upload/get 都用本地桩；
- 每 3 秒跑任务队列；
- WS 中枢不可用，App 自动退回 HTTP；
- 夹具视频 200KB；默认片大小通常不切片，测切片可用启动日志里的 64KB `curl`。

管理台本地：

```bash
npm run dev:admin
```

Vite 代理 `/admin/api`、`/media` 到 8787。

Windows 防火墙：

```powershell
netsh advfirewall firewall add rule name="favkit-local-worker" dir=in action=allow protocol=TCP localport=8787 profile=private
```

## 9. 测试

```bash
cd backend-in-worker
node --test --test-concurrency=1 test/*.test.mjs
```

当前 **74 例，72 pass / 2 个大文件用例按环境跳过**。

大文件用例需要本机 nginx `:9200` 和切片服务 `:3000`：

```bash
node --test test/large-file.test.mjs
```

## 10. 已知边界

1. **默认所有 backup 上传依赖 `MEDIA_BASE_URL`**：切片合成、整文件/图片/封面代理都要存储服务能访问 Worker。设为 false 后仅切片依赖。
2. **Workers Cache 无法在本地测试环境真实执行**：上线后必须用 `cf-cache-status` 验证。
3. **缓存探测会填充缓存**：管理台“检查/预热”不是纯只读；冷 MISS 可能拉完整对象。
4. **缓存按边缘位置**：一次 HIT 不代表所有地区命中；WAN/不同 PoP 可能独立 MISS。
5. **删除远端 backup 仍未接**：删收藏不会清理外部存储文件。
6. **后台不是实时**：解析/备份最多滞后一个 cron 周期；写入后的 `waitUntil` 可能被 CPU 预算截断。
7. **D1 单库 500MB**：只存元数据与临时 init，不存媒体正文。
8. **管理台只读**：重试、删除、清缓存等写操作还没接；`ADMIN_HOST` 为空时管理 API 不限制 Host，生产必须设置并配 Access。
9. **管理台不显示 App 本地缓存**：App 本地缓存需要 App 自己上报或调试页查看。

## 11. 相关文档

- [同步后台接口说明](../docs/同步后台接口说明.md)
- [Cloudflare 媒体缓存](../docs/Cloudflare媒体缓存配置.md)
- [架构与性能验证](../docs/架构与性能验证.md)
- [管理台开发说明](pages/README.md)