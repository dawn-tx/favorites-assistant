import { jsonResponse } from '../http.js';
import { DEFAULT_SLICE_RULE } from '../config.js';
import { parserConfig } from '../policy/parser.js';

/** GET /meta：免鉴权能力自述。App 不调用，联调第一步用 */
export function handleMeta(cfg) {
  return jsonResponse(200, {
    ok: true,
    server_time: Date.now(),
    cursor_style: 'keyset:favorite_at_desc,url_asc',
    cursor_note: '复合 keyset 分页：favorite_at 降序、并列时按 url 升序；游标是不透明串（favorite_at.url），原样回传',
    min_fields: ['url', 'favorite_at', 'updated_at', 'media_state'],
    lookup_max_urls: cfg.lookupMaxUrls,
    media_states: ['pending', 'ready', 'failed'],
    resolve_requires_auth: cfg.syncToken !== '',
    next_wait_ms: cfg.uploadWaitMs,
    next_wait_jitter_ms: cfg.uploadWaitJitterMs,
    resolver: {
      name: 'parser-service',
      parser_service: parserConfig.url ? 'remote' : 'none',
      note: '素材由解析服务返回，后台只做提取/选档/切片/代理；media 就绪不等上传',
    },
    media_url_style: 'relative-to-endpoint',
    media_path: 'media/<key>.<ext>',
    media_path_note: '段地址是 /media/<key>.<ext>，相对的是接口地址（含 BASE_PATH），App 负责拼接',
    media_full_path: 'media/full/<key>.<ext>',
    media_full_note: '同 key 的整段代理：忽略 Range，成功只回 200 且 accept-ranges: none；错误仍 404/502',
    media_requires_auth: false,
    media_cache_control: 'public, max-age=31536000, immutable',
    ready_on_parse: true,
    storage: 'd1+external',
    slice_rule: cfg.defaultSliceRule ?? DEFAULT_SLICE_RULE,
    slice_service: cfg.sliceServiceUrl ? 'remote' : 'none',
    slice_info: {
      note: '切片信息只在备份上传前/重试期间临时保存；上传成功后立即清掉，不再按 TTL 长期保留',
    },
    upload: {
      max_attempts: cfg.uploadMaxAttempts,
      retry_ms: cfg.uploadRetryMs,
      storage: 'external',
      covers: cfg.backupCovers,
      batch_size: cfg.storageBatchSize,
      max_slices_per_favorite: cfg.maxSlicesPerFavorite,
      note: '只上传 backup：长视频切片、短视频整文件、备用图；开启 BACKUP_COVERS 时额外上传视频封面备份。primary 视频/图片/封面默认都不进存储。切片传 Worker 的临时合成地址，短视频/备用图/封面直传源地址；单次最多 10 条，全部成功才把 backup 标 ready',
    },
    scheduler: {
      kind: 'cron',
      every_ms: 180_000,
      jobs_per_tick: cfg.jobsPerTick,
      upload_jobs_per_tick: cfg.uploadJobsPerTick,
      upload_items_per_tick: cfg.uploadItemsPerTick,
      note: 'Workers 没有常驻进程：解析/备份是 D1 队列 + 每 3 分钟 cron；免费版一次调用最多 50 个子请求，所以每轮只跑一小批',
    },
    ws: {
      path: 'ws',
      requires_auth: cfg.syncToken !== '',
      messages: ['hello', 'list', 'lookup', 'resolve', 'ping'],
      push: ['favorites', 'deleted'],
      note: '控制面 WebSocket：清单、lookup、重新准备与推送走 WS；上传仍走 POST /sync，媒体仍走 HTTP。推送是完整收藏行，删除推 url 列表',
    },
  });
}