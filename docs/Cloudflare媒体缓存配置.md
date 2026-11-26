# Cloudflare 媒体缓存

适用对象：`backend-in-worker` 的媒体代理、Workers Cache 和管理台“检查/预热”。

部署、D1、自定义域名、Access 和完整配置见 [backend-in-worker/README.md](../backend-in-worker/README.md)。本文只讲缓存语义。

## 1. 先对齐概念

- **primary**：源站返回的视频/图片，没有长短之分。
- **backup**：上传到外部存储的副本。是否切片由 `slice_rule.bytes` / `DEFAULT_SLICE_RULE` 的**切片阈值**决定。
- **`PRIMARY_CACHE_MAX_BYTES`**：只决定 primary 视频是否进入 Workers Cache，和 backup 切片阈值独立。

一个视频可能同时：

- 超过切片阈值，所以 backup 是切片；
- 未超过缓存阈值，所以 primary 视频仍然缓存。

两者不是同一个阈值。

## 2. 对外只有一个媒体路径

App 始终请求：

```text
/media/<key>.<ext>
```

后台在 key 前生成内部标记：

```text
/media/c_<key>   缓存入口
/media/n_<key>   普通 Range 入口
```

- `c_` / `n_` 是内部路由标记，不是第二个公开接口；
- 同一个素材的 URL 在缓存命中前后不变；
- App 不需要识别标记，也不需要切换请求；
- 旧的无标记 `/media/<key>` 仍按普通入口处理。

为什么标记必须进 key：Workers Cache 在 Worker 执行前判断缓存，无法先查 D1 再决定；否则超过阈值的 primary 视频会被整对象缓存，或每次命中都要先查 D1。

## 3. 缓存什么

| 媒体 | 路径 | 缓存 |
|---|---|---|
| 所有 backup（切片/整文件/备用图/可选封面） | `c_` | 是 |
| primary 图片/封面 | `c_` | 是 |
| primary 视频，源文件 ≤ `PRIMARY_CACHE_MAX_BYTES` | `c_` | 是 |
| primary 视频，超过阈值或大小未知 | `n_` | 否，Range 代理 |
| 管理台静态资源 | `/assets/*` | assets-first，不进 Worker Cache |
| 管理台 API | `/admin/api/*` | 否，`no-store` |

`MediaCache` 入口还会按 asset 类型二次检查。即使手动把超过阈值的 primary 视频改成 `c_`，也会返回 404，不会写入缓存。

## 4. 配置

### 4.1 primary 视频缓存阈值

```toml
PRIMARY_CACHE_MAX_BYTES = "18874368"
```

- 默认 18,874,368 字节；
- `0` = 不缓存任何 primary 视频；
- 只影响 primary 视频；backup 始终缓存，primary 图片/封面始终缓存；
- 源文件大小为 0/未知时按不缓存处理。

### 4.2 backup 切片阈值

```text
slice_rule.bytes（App 请求可带，按收藏落库）
DEFAULT_SLICE_RULE.bytes（没有带规则时的默认值，当前 18MiB）
```

- 只决定 backup 是切片还是整文件；
- 不决定 primary 是否缓存；
- 与 `PRIMARY_CACHE_MAX_BYTES` 可以不同。

### 4.3 per-entrypoint Workers Cache

当前 [wrangler.toml](../backend-in-worker/wrangler.toml)：

```toml
[cache]
enabled = false
cross_version_cache = true

[exports.default]
type = "worker"

  [exports.default.cache]
  enabled = false

[exports.MediaCache]
type = "worker"

  [exports.MediaCache.cache]
  enabled = true
```

- 默认入口不缓存：API、静态资源、`n_` 媒体都不会被 Workers Cache 整对象缓存；
- 只有 `MediaCache` 入口开启缓存；
- `cross_version_cache=true` 让部署后继续复用已有对象；媒体 key 已带内容版本，安全；
- 缓存命中不执行 Worker、不计 CPU，但仍按一次 Worker 请求计费。

## 5. Range 行为

Workers Cache 对 `c_` 媒体：

1. 去掉客户端 `Range`；
2. 让 Worker 返回完整 `200`；
3. 缓存完整对象；
4. 自己切出客户端请求的 `206`。

所以：

- `c_` 媒体响应必须是完整 `200`；
- 后续 Range/Seek 可以命中缓存；
- `n_` 媒体仍按普通 Range 代理，不经过 Workers Cache。

## 6. 上传时会不会填充缓存

默认 `STORAGE_MEDIA_VIA_WORKER=true`，所有 backup 上传地址都是 `c_`：

- 切片：Worker 合成 `init + 源区间`，存储回源 `c_` 地址；
- 整文件/图片/封面：Worker 流式代理完整文件，存储回源 `c_` 地址；
- 这次存储回源通常就是第一次 MediaCache 填充；
- 上传完成后 App 请求同一 URL，有机会直接命中。

`STORAGE_MEDIA_VIA_WORKER=false` 时，整文件/图片/封面直传源站，不填充 Worker 缓存；切片仍走 Worker。

缓存填充前提：

- 同一个 Worker、同一个 `c_` 路径；
- 响应是 `200` + `public`；
- 没有部署清缓存、purge 或 LRU 淘汰。

App 本地缓存不会被上传填充；只有 App 真正播放或预取后才有。

## 7. 管理台“检查/预热”

管理台由 [pages/](../backend-in-worker/pages/) 构建到 `pages/dist`，由 Worker 的 `[assets]` assets-first 提供；静态资源不进入 Workers Cache。

“检查/预热”由浏览器发：

```text
HEAD media_base + /media/c_...
```

读取：

```text
cf-cache-status
age
```

- `HIT`：当前请求命中边缘缓存；
- `MISS` / `EXPIRED`：未命中，可能触发完整对象填充；
- 没有 `cf-cache-status`：本地联调或非缓存路径；
- 结果只代表当前请求位置，不代表所有地区；
- 所有 backup 可检查；primary 视频只有服务端生成 `c_` 时才显示按钮。

## 8. 验证

```bash
curl -I "https://api.example.com/api/v1/media/c_<key>.mp4"
curl -I -H "Range: bytes=0-1023" "https://api.example.com/api/v1/media/c_<key>.mp4"
```

预期：

```text
cache-control: public, max-age=31536000, immutable
cf-cache-status: MISS / HIT
```

- 第一次可能是 `MISS`，同时填充缓存；
- 后续 Range 请求应为 `206 + HIT`；
- 一直 `MISS` 时检查：是否用了不同 Worker、不同路径、`no-store`、部署清缓存，或对象超过 512MB。

`n_` 路径不应出现 Workers Cache 的 `HIT`；它用于超过阈值的 primary 视频，保持普通 Range。

## 9. 边界

1. **Workers Cache 是整对象缓存**：适合 backup、图片、未超过阈值的 primary 视频；超过阈值的 primary 视频不要用。
2. **512MB 上限**：当前 Workers Cache 按 Free 的可缓存对象上限执行，超过的素材不会被缓存。
3. **缓存不是存储**：外部存储仍是 backup 的唯一真相。
4. **缓存按边缘位置**：一次 HIT 不代表所有地区都命中。
5. **探测会改变缓存**：冷 MISS 的“检查”可能顺带预热。
6. **静态资源不缓存**：`[assets]` assets-first，`run_worker_first` 只匹配 `/admin/api/*`、`/api/*`、`/media/*`。
7. **没有全局 `[cache] enabled=true`**：默认入口关闭，只有 `MediaCache` entrypoint 开启。
8. **管理台不显示 App 本地缓存**：App 本地缓存需要 App 上报或调试页。