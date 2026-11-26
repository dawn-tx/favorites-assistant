# policy 层边界

这个目录是后台最容易按外部服务调整的地方。改之前先区分两类代码：

- **稳定契约**：core 依赖字段名/返回形状，不能随手改。
- **常改适配**：外部服务字段、地址、协议、策略，优先在这里改。

| 文件 | 常改什么 | 不能改什么 |
|---|---|---|
| `parser.js` | `parserConfig`（url/token/timeout）、请求路径/参数/鉴权方式；以后多解析器也在这里扩 | `resolveMedia(cfg,url,budget)` 的入参与返回 `ResolvedMedia`；上层不直接读原始响应 |
| `parser-normalize.js` | 解析服务原始字段映射、去重、清晰度推断 | 返回 `ResolvedMedia` 形状：`title/author/site/summary/videos[]/images[]`；视频项的 `title/durationMs/coverUrl/variants[]`；variant 的 `url/height/quality` |
| `selection.js` | 选档顺序、探活策略 | 返回 `{ variant, probe }`；`probe` 至少有 `ok/size/etag/rangeSupported` |
| `slicing.js` | 切片服务请求字段映射、响应字段映射 | 返回 `{ sourceUrl, etag, fileSize, slices[] }`；每片至少有 `start/end/mediaSize/initBytes/initHash/durationMs`；`end` 是闭区间 |
| `storage-errors.js` | 无；限流错误形状固定 | `StorageThrottleError` 必须带 `retryAfterMs`；上传/下载都用它表示 429 |
| `storage-upload.js` | 媒体地址规则、存储上传协议细节 | `uploadAssetsToStorage(cfg,assets,favorite)` 返回与入参顺序一致的 id 数组；429 必须抛 `StorageThrottleError`（带 `retryAfterMs`）；请求体形状 `{type,media,favorite:{url,summary}}` |
| `storage-download.js` | 下载 URL 拼接、鉴权、响应头处理 | `downloadStoredMedia` 返回 `Response | null`；转发 Range/HEAD；429 抛 `StorageThrottleError`（带 `retryAfterMs`）；成功保留 content-* 与 immutable 缓存头；`null` 由代理回 502 |
| `index.js` | 新增策略导出 | 不要重命名已有导出；core 只从这里取策略 |

## 快速判断

- 换解析服务：改 `parser.js` 的 `parserConfig` 和请求形状，字段不兼容再改 `parser-normalize.js`。
- 换存储服务：改 `storage-upload.js` / `storage-download.js`。
- 改选档或切片策略：改 `selection.js` / `slicing.js`，但保持返回值。
- 只想改媒体上传走 Worker 还是源站：用 `STORAGE_MEDIA_VIA_WORKER` 控制，默认 `true`；代码在 `storage-upload.js` 的 `mediaUrl`。

`parse.js`、`runner.js`、`media/proxy.js` 只消费这些返回值，不负责理解外部服务字段。