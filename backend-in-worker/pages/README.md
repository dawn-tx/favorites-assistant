# Favorites Admin

独立 Web 管理台，不进入 Android App。Vue 3 + JavaScript + Vite + Tailwind。

## 本地开发

先启动本地 Worker：

```bash
cd backend-in-worker
npm run dev:local
```

再启动前端：

```bash
cd backend-in-worker
npm run dev:admin
```

Vite 会把 `/admin/api` 和 `/media` 代理到 `127.0.0.1:8787`。管理台地址是 `http://127.0.0.1:5173`。

## 构建

```bash
cd backend-in-worker
npm run build:admin
```

输出到 `pages/dist`，由 Worker 的 `[assets]` 提供。生产入口建议：

- `admin.example.com` → 管理台
- `api.example.com` → App API / 媒体
- `admin.example.com` 前套 Cloudflare Access
- `ADMIN_HOST=admin.example.com`

## 页面

- 概览：收藏状态、任务、上传异常
- 收藏与媒体：详情 Drawer、backup 状态、媒体检查/预热
- 任务队列：只读 jobs
- “检查/预热”会发真实 `HEAD`；冷缓存可能触发完整对象填充。所有 backup 可缓存；primary 视频是否进入缓存由服务端阈值决定，批量前先确认大小。

管理 API 目前只读；重试、删除、清缓存等写操作后续再加。