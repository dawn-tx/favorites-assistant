import { jsonResponse } from '../http.js';
import { DEFAULT_SLICE_RULE } from '../config.js';

/** GET /meta：免鉴权能力自述。App 不调用，联调第一步用 */
export function handleMeta(env, cfg) {
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
      parser_service: cfg.parseServiceUrl ? 'remote' : 'none',
      note: '素材由解析服务返回，后台只做提取/选档/切片/代理；media 就绪不等上传',
    },
    media_url_style: 'relative-to-endpoint',
    media_path: 'media/<key>.<ext>',
    media_path_note: '段地址是 /media/<key>.<ext>，相对的是接口地址（含 BASE_PATH），App 负责拼接',
    media_requires_auth: false,
    media_cache_control: 'public, max-age=31536000, immutable',
    ready_on_parse: true,
    storage: 'd1+external',
    slice_rule: cfg.defaultSliceRule ?? DEFAULT_SLICE_RULE,
    slice_service: cfg.sliceServiceUrl ? 'remote' : 'none',
    slice_info_cache: {
      ttl_ms: cfg.sliceInfoTtlMs,
      cleanup_ms: 60 * 60 * 1000,
      touch_ms: cfg.sliceInfoTouchMs,
      note: '切片的 init 与区间是派生缓存：已有备份且空闲超过 ttl 的清掉，代理时按索引重新向切片服务补取；没有备份的片不清理。清理由每小时 :23 的 cron 执行',
    },
    upload: {
      max_height: cfg.uploadMaxHeight,
      max_attempts: cfg.uploadMaxAttempts,
      retry_ms: cfg.uploadRetryMs,
      storage: 'external',
      covers: true,
      batch_size: cfg.storageBatchSize,
      max_slices_per_favorite: cfg.maxSlicesPerFavorite,
      note: '每个视频只传一档；超过片大小才切片；图片与封面全传。上传时把本服务的虚拟媒体地址交给外部存储服务回源转存，单次最多 10 条，返回的 id 写回 store_id；单条收藏切片总数有上限，超出的视频退回整片',
    },
    scheduler: {
      kind: 'cron',
      every_ms: 60_000,
      jobs_per_tick: cfg.jobsPerTick,
      upload_jobs_per_tick: cfg.uploadJobsPerTick,
      upload_items_per_tick: cfg.uploadItemsPerTick,
      note: 'Workers 没有常驻进程：解析/备份是 D1 队列 + 每分钟 cron；免费版一次调用最多 50 个子请求，所以每轮只跑一小批',
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