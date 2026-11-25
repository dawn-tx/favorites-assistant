/**
 * 可替换策略层：core 只通过这里使用它们。
 *
 * 稳定契约（上层依赖，不要改形状）：
 *   - ResolvedMedia: parser-normalize
 *   - pickReachableVariant -> { variant, probe }
 *   - fetchSlices -> { sourceUrl, etag, fileSize, slices[] }
 *   - uploadAssetsToStorage -> ordered id[]
 *   - downloadStoredMedia -> Response | null
 *
 * 常改适配：parser（服务地址/令牌/请求形状）、parser-normalize（外部字段）、
 * storage-upload/storage-download（存储协议）。
 */
export { parserConfig, resolveMedia } from './parser.js';
export { normalize } from './parser-normalize.js';
export { pickReachableVariant } from './selection.js';
export { fetchSlices, fetchSlicesShared } from './slicing.js';
export {
  MAX_BATCH,
  buildUploadItems,
  uploadAssetsToStorage,
} from './storage-upload.js';
export { StorageThrottleError } from './storage-errors.js';
export { downloadStoredMedia, deleteStoredMedia } from './storage-download.js';