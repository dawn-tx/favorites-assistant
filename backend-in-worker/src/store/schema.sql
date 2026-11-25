-- 一个人的收藏库：url 即身份，没有账号、版本号与冲突裁决。
-- 媒体备份在外部存储服务，assets.store_id 是对方返回的 id。
--
-- 压缩口径：
--   * 收藏行不再存 asset_keys；段顺序由 assets.ord 决定。
--   * 源文件信息（url/etag/大小/清晰度）按 (url, etag, size) 去重到 sources，asset 只存 source_id。
--   * 每片重复的 init 段按内容哈希去重到 inits，asset 只存 init_id 与字节区间。
--   * asset 不再重复存收藏 url，只存 favorite_id。

CREATE TABLE IF NOT EXISTS favorites (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  url                TEXT NOT NULL UNIQUE,
  shard              INTEGER NOT NULL DEFAULT 0,
  note               TEXT NOT NULL DEFAULT '',
  tags               TEXT NOT NULL DEFAULT '[]',
  source_app         TEXT NOT NULL DEFAULT '',
  favorite_at        INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL,
  media              TEXT,
  media_state        TEXT NOT NULL DEFAULT 'pending',
  failed_reason      TEXT NOT NULL DEFAULT '',
  resolved_at        INTEGER NOT NULL DEFAULT 0,
  slice_rule         TEXT,
  upload_state       TEXT NOT NULL DEFAULT 'pending',
  upload_updated_at  INTEGER NOT NULL DEFAULT 0,
  upload_attempts    INTEGER NOT NULL DEFAULT 0,
  upload_next_at     INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS favorites_order ON favorites (favorite_at DESC, url ASC);

-- 同一 url 的不同内容版本各占一行，避免内容变化后旧 asset 读错源信息
CREATE TABLE IF NOT EXISTS sources (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  url         TEXT NOT NULL,
  etag        TEXT NOT NULL DEFAULT '',
  file_size   INTEGER NOT NULL DEFAULT 0,
  quality     TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL,
  UNIQUE (url, etag, file_size)
);

-- 切片 init 段按内容哈希共享；存原始字节（BLOB），不存 base64 文本。
-- asset 被清理时由 cron 回收无引用行。
CREATE TABLE IF NOT EXISTS inits (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  hash        TEXT NOT NULL UNIQUE,
  bytes       BLOB NOT NULL,
  created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS assets (
  key            TEXT PRIMARY KEY,
  favorite_id    INTEGER NOT NULL,
  source_id      INTEGER NOT NULL,
  init_id        INTEGER,
  ord            INTEGER NOT NULL DEFAULT 0,
  kind           TEXT NOT NULL,
  role           TEXT NOT NULL DEFAULT 'primary',
  video_index    INTEGER,
  slice_index    INTEGER,
  image_index    INTEGER,
  title          TEXT NOT NULL DEFAULT '',
  duration_ms    INTEGER NOT NULL DEFAULT 0,
  cover_key      TEXT NOT NULL DEFAULT '',
  start_offset   INTEGER,
  media_size     INTEGER,
  store_id       TEXT NOT NULL DEFAULT '',
  error          TEXT NOT NULL DEFAULT '',
  backup_state   TEXT NOT NULL DEFAULT 'none',
  backup_error   TEXT NOT NULL DEFAULT '',
  last_used_at   INTEGER NOT NULL DEFAULT 0,
  slice_cleaned  INTEGER NOT NULL DEFAULT 0
);

-- primary 视频的每一档各一行；key 是这个档独立的虚拟媒体地址
CREATE TABLE IF NOT EXISTS variants (
  key            TEXT PRIMARY KEY,
  favorite_id    INTEGER NOT NULL,
  video_index    INTEGER NOT NULL,
  variant_index  INTEGER NOT NULL,
  source_id      INTEGER NOT NULL,
  quality        TEXT NOT NULL DEFAULT '',
  height         INTEGER NOT NULL DEFAULT 0,
  active         INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS variants_by_favorite ON variants (favorite_id, video_index, variant_index);
CREATE INDEX IF NOT EXISTS variants_by_source ON variants (source_id);
-- 每个视频只能有一条 active
CREATE UNIQUE INDEX IF NOT EXISTS variants_one_active ON variants (favorite_id, video_index) WHERE active = 1;

CREATE INDEX IF NOT EXISTS assets_by_favorite ON assets (favorite_id, ord);
CREATE INDEX IF NOT EXISTS assets_by_source ON assets (source_id);
CREATE INDEX IF NOT EXISTS assets_by_init ON assets (init_id);
CREATE INDEX IF NOT EXISTS assets_cleanup ON assets (store_id, last_used_at);

-- 单线程语义换成「可抢占的锁」：一个 tick 只领自己的几条，处理不完就放回去。
CREATE TABLE IF NOT EXISTS jobs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  url          TEXT NOT NULL,
  kind         TEXT NOT NULL,
  attempts     INTEGER NOT NULL DEFAULT 0,
  next_at      INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS jobs_once ON jobs (url, kind);
CREATE INDEX IF NOT EXISTS jobs_ready ON jobs (next_at, locked_until);