/**
 * 可替换策略层：这四块是后续最容易按需求调整的部分，core 只通过这里使用它们。
 *
 * - parser   外部解析服务原始字段 → ResolvedMedia
 * - selection 多分辨率选档与探活顺序
 * - slicing  切片服务请求、响应归一化与单飞
 * - storage  外部存储上传请求体（含 favorite）与下载/删除框架
 */
export { normalize, resolveMedia } from './parser.js';
export { pickReachableVariant } from './selection.js';
export { fetchSlices, fetchSlicesShared } from './slicing.js';
export {
  MAX_BATCH,
  StorageThrottleError,
  buildUploadItems,
  uploadAssetsToStorage,
  downloadStoredMedia,
  deleteStoredMedia,
} from './storage.js';