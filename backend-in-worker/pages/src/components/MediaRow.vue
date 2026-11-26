<script setup>
import { computed } from 'vue';
import { AlertTriangle, ExternalLink, RefreshCw } from 'lucide-vue-next';
import StatusBadge from './StatusBadge.vue';
import { absoluteMediaUrl } from '../lib/api';
import { formatBytes, formatTime } from '../lib/format';

const props = defineProps({
  label: { type: String, required: true },
  url: { type: String, default: '' },
  mediaBase: { type: String, default: '' },
  cacheable: { type: Boolean, default: false },
  result: { type: Object, default: null },
  busy: { type: Boolean, default: false },
  kind: { type: String, default: '' },
  size: { type: [Number, String], default: 0 },
});
const emit = defineEmits(['probe']);

const absoluteUrl = computed(() => absoluteMediaUrl(props.mediaBase, props.url));
const cacheTone = computed(() => {
  if (!props.result) return 'unknown';
  if (props.result.cacheStatus === 'HIT') return 'ready';
  if (props.result.cacheStatus === 'MISS' || props.result.cacheStatus === 'EXPIRED') return 'pending';
  if (props.result.error || !props.result.ok) return 'failed';
  return 'unknown';
});
const cacheLabel = computed(() => {
  if (!props.result) return '未检查';
  if (props.result.error) return '请求失败';
  if (props.result.cacheStatus) return props.result.cacheStatus;
  return `HTTP ${props.result.status}`;
});
</script>

<template>
  <div class="rounded-xl border border-slate-200/80 bg-white px-3.5 py-3">
    <div class="flex items-start gap-3">
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-2">
          <span class="text-xs font-medium text-slate-800">{{ label }}</span>
          <StatusBadge v-if="kind" :state="kind === 'video' ? 'pending' : 'unknown'" :label="kind === 'video' ? '视频' : '图片'" />
          <span v-if="size" class="text-[10px] text-slate-400">{{ formatBytes(size) }}</span>
        </div>
        <p class="mt-1.5 truncate font-mono text-[10px] text-slate-400" :title="url">{{ url }}</p>
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <StatusBadge :state="cacheTone" :label="cacheLabel" dot />
          <span v-if="result?.age" class="text-[10px] text-slate-400">age {{ result.age }}s</span>
          <span v-if="result?.checkedAt" class="text-[10px] text-slate-400">{{ formatTime(result.checkedAt) }}</span>
          <span v-if="result?.elapsedMs != null" class="text-[10px] text-slate-400">{{ result.elapsedMs }} ms</span>
        </div>
        <p v-if="result?.error" class="mt-1 text-[10px] text-rose-500">{{ result.error }}</p>
      </div>
      <div class="flex shrink-0 items-center gap-1.5">
        <a
          v-if="absoluteUrl"
          :href="absoluteUrl"
          target="_blank"
          rel="noreferrer"
          class="rounded-md p-1.5 text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
          title="打开媒体"
        >
          <ExternalLink class="size-3.5" />
        </a>
        <button
          v-if="cacheable"
          class="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-2 py-1.5 text-[10px] font-medium text-slate-600 transition hover:border-indigo-200 hover:text-indigo-600 disabled:opacity-50"
          :disabled="busy"
          title="冷缓存会触发完整对象填充"
          @click="emit('probe', url)"
        >
          <RefreshCw class="size-3" :class="busy ? 'animate-spin' : ''" />
          检查/预热
        </button>
        <span v-else class="rounded-md bg-slate-50 px-2 py-1.5 text-[10px] text-slate-400">不缓存</span>
      </div>
    </div>
    <p v-if="cacheable" class="mt-2 flex items-start gap-1.5 text-[10px] leading-4 text-amber-600">
      <AlertTriangle class="mt-0.5 size-3 shrink-0" />
      冷缓存会触发完整对象填充；primary 视频是否走这里由服务端阈值决定，批量前先确认大小。
    </p>
  </div>
</template>