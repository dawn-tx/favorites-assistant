<script setup>
import { computed, reactive } from 'vue';
import { ExternalLink, Layers3, X } from 'lucide-vue-next';
import MediaRow from './MediaRow.vue';
import StatusBadge from './StatusBadge.vue';
import { probeMedia } from '../lib/api';
import { formatFullTime, stateLabel } from '../lib/format';

const props = defineProps({
  item: { type: Object, required: true },
  mediaBase: { type: String, default: '' },
});
const emit = defineEmits(['close']);

const probes = reactive({});
const busy = reactive({});
const segments = computed(() => props.item.media?.segments ?? []);

function isCacheable(url) {
  return typeof url === 'string' && url.includes('/media/c_');
}

async function probe(url) {
  if (!isCacheable(url) || busy[url]) return;
  busy[url] = true;
  try {
    probes[url] = await probeMedia(`${props.mediaBase.replace(/\/+$/, '')}${url}`);
  } finally {
    busy[url] = false;
  }
}

function backupTone(state) {
  return state || 'none';
}
</script>

<template>
  <div class="fixed inset-0 z-50 flex justify-end bg-slate-950/30 backdrop-blur-[2px]" @click.self="emit('close')">
    <aside class="drawer-panel flex h-full w-full max-w-[620px] flex-col border-l border-slate-200 bg-white shadow-2xl">
      <header class="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
        <div class="grid size-9 shrink-0 place-items-center rounded-lg bg-indigo-50 text-indigo-600">
          <Layers3 class="size-4" />
        </div>
        <div class="min-w-0 flex-1">
          <p class="text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-400">Media detail</p>
          <h2 class="mt-1 truncate text-sm font-semibold text-slate-900" :title="item.url">{{ item.url }}</h2>
          <p class="mt-1 truncate text-[11px] text-slate-400">{{ item.note || '无备注' }}</p>
        </div>
        <button class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-700" @click="emit('close')">
          <X class="size-4" />
        </button>
      </header>

      <div class="flex-1 space-y-5 overflow-y-auto px-5 py-5 scrollbar-thin">
        <section class="grid grid-cols-2 gap-3">
          <div class="card-muted px-3.5 py-3">
            <p class="text-[10px] uppercase tracking-wider text-slate-400">内容状态</p>
            <div class="mt-2"><StatusBadge :state="item.media_state" /></div>
          </div>
          <div class="card-muted px-3.5 py-3">
            <p class="text-[10px] uppercase tracking-wider text-slate-400">更新时间</p>
            <p class="mt-2 text-xs text-slate-700">{{ formatFullTime(item.updated_at) }}</p>
          </div>
        </section>

        <section v-if="item.failed_reason" class="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-700">
          {{ item.failed_reason }}
        </section>

        <section class="space-y-3">
          <div class="flex items-center justify-between">
            <h3 class="text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">Primary segments</h3>
            <span class="text-[10px] text-slate-400">{{ segments.length }} 段</span>
          </div>

          <div v-if="segments.length === 0" class="rounded-xl border border-dashed border-slate-200 px-4 py-8 text-center text-xs text-slate-400">
            内容尚未就绪
          </div>

          <article v-for="segment in segments" :key="segment.index" class="card overflow-hidden">
            <div class="flex items-start justify-between gap-3 border-b border-slate-100 px-4 py-3">
              <div class="min-w-0">
                <p class="truncate text-sm font-medium text-slate-900">{{ segment.title || `片段 ${segment.index + 1}` }}</p>
                <div class="mt-1.5 flex flex-wrap items-center gap-2 text-[10px] text-slate-400">
                  <span>{{ segment.kind === 'video' ? '视频' : '图片' }}</span>
                  <span v-if="segment.quality">· {{ segment.quality }}</span>
                  <span v-if="segment.duration_ms">· {{ Math.round(segment.duration_ms / 1000) }}s</span>
                </div>
              </div>
              <StatusBadge :state="backupTone(segment.backup?.state)" :label="`备份 ${stateLabel(segment.backup?.state)}`" />
            </div>

            <div class="space-y-2 p-3">
              <MediaRow
                label="Primary"
                :url="segment.url"
                :media-base="mediaBase"
                :cacheable="isCacheable(segment.url)"
                :result="probes[segment.url]"
                :busy="busy[segment.url]"
                :kind="segment.kind"
                @probe="probe"
              />
              <MediaRow
                v-if="segment.cover_url"
                label="封面"
                :url="segment.cover_url"
                :media-base="mediaBase"
                :cacheable="isCacheable(segment.cover_url)"
                :result="probes[segment.cover_url]"
                :busy="busy[segment.cover_url]"
                kind="image"
                @probe="probe"
              />

              <div v-if="segment.backup?.items?.length" class="space-y-2 border-t border-slate-100 pt-3">
                <p class="px-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-slate-400">Backup items</p>
                <MediaRow
                  v-for="(item, index) in segment.backup.items"
                  :key="`${item.url}-${index}`"
                  :label="item.title || `备用 ${index + 1}`"
                  :url="item.url"
                  :media-base="mediaBase"
                  :cacheable="isCacheable(item.url)"
                  :result="probes[item.url]"
                  :busy="busy[item.url]"
                  :kind="item.kind"
                  @probe="probe"
                />
              </div>
              <p v-else class="px-1 text-[11px] text-slate-400">没有备用项</p>
            </div>
          </article>
        </section>

        <section class="rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-[11px] leading-5 text-slate-500">
          <p class="font-medium text-slate-700">缓存检查说明</p>
          <p class="mt-1">检查会发一次真实媒体请求；如果边缘缓存是 MISS，Workers Cache 会把完整对象写入缓存。所有 backup 都可缓存；primary 视频按服务端阈值判断，批量前先确认大小。</p>
          <a class="mt-2 inline-flex items-center gap-1 text-indigo-600 hover:text-indigo-700" :href="item.url" target="_blank" rel="noreferrer">
            收藏链接 <ExternalLink class="size-3" />
          </a>
        </section>
      </div>
    </aside>
  </div>
</template>