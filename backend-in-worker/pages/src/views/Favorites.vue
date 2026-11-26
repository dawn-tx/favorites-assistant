<script setup>
import { computed, onMounted, ref } from 'vue';
import { ChevronRight, Inbox, RefreshCw, Search } from 'lucide-vue-next';
import MediaDetailDrawer from '../components/MediaDetailDrawer.vue';
import StatusBadge from '../components/StatusBadge.vue';
import { getJson } from '../lib/api';
import { formatTime } from '../lib/format';

const rows = ref([]);
const mediaBase = ref('');
const cursor = ref('');
const hasMore = ref(false);
const total = ref(0);
const loading = ref(true);
const loadingMore = ref(false);
const error = ref('');
const query = ref('');
const selected = ref(null);

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  if (!keyword) return rows.value;
  return rows.value.filter((row) =>
    [row.url, row.note, ...(row.tags ?? [])].some((value) => String(value ?? '').toLowerCase().includes(keyword)),
  );
});

function backupSummary(row) {
  const states = (row.media?.segments ?? []).map((segment) => segment.backup?.state ?? 'none');
  if (states.length === 0) return { state: 'unknown', label: '无内容' };
  if (states.every((state) => state === 'ready')) return { state: 'ready', label: '备份完成' };
  if (states.some((state) => state === 'failed')) return { state: 'failed', label: '备份失败' };
  if (states.some((state) => state === 'pending')) return { state: 'pending', label: '备份中' };
  return { state: 'none', label: '未备份' };
}

async function load(more = false) {
  if (more) loadingMore.value = true;
  else loading.value = true;
  error.value = '';
  try {
    const suffix = more && cursor.value ? `&cursor=${encodeURIComponent(cursor.value)}` : '';
    const data = await getJson(`/favorites?limit=50${suffix}`);
    mediaBase.value = data.media_base || mediaBase.value;
    rows.value = more ? [...rows.value, ...data.favorites] : data.favorites;
    cursor.value = data.next_cursor || '';
    hasMore.value = Boolean(data.has_more);
    total.value = data.total ?? rows.value.length;
  } catch (err) {
    error.value = err.message || '加载失败';
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

onMounted(() => load(false));
</script>

<template>
  <div class="space-y-5">
    <section class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p class="text-sm text-slate-500">查看收藏内容、媒体段与备份状态；点击行打开右侧详情。</p>
        <p class="mt-1 text-[11px] text-slate-400">共 {{ total }} 条 · 当前已加载 {{ rows.length }} 条</p>
      </div>
      <button
        class="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-medium text-slate-600 shadow-sm transition hover:border-slate-300 hover:text-slate-900 disabled:opacity-50"
        :disabled="loading"
        @click="load(false)"
      >
        <RefreshCw class="size-3.5" :class="loading ? 'animate-spin' : ''" />
        刷新
      </button>
    </section>

    <section class="card overflow-hidden">
      <div class="flex flex-wrap items-center gap-3 border-b border-slate-100 px-4 py-3">
        <div class="relative min-w-[220px] flex-1">
          <Search class="pointer-events-none absolute left-3 top-1/2 size-3.5 -translate-y-1/2 text-slate-400" />
          <input
            v-model="query"
            type="search"
            placeholder="搜索 URL、备注或标签"
            class="h-9 w-full rounded-lg border border-slate-200 bg-slate-50/70 pl-9 pr-3 text-xs text-slate-700 outline-none transition placeholder:text-slate-400 focus:border-indigo-300 focus:bg-white focus:ring-2 focus:ring-indigo-100"
          />
        </div>
        <span class="text-[11px] text-slate-400">筛选当前页 {{ filtered.length }} 条</span>
      </div>

      <div v-if="error" class="border-b border-rose-100 bg-rose-50 px-4 py-3 text-xs text-rose-700">
        {{ error }}
        <button class="ml-2 font-medium underline underline-offset-2" @click="load(false)">重试</button>
      </div>

      <div v-if="loading" class="space-y-2 p-4">
        <div v-for="i in 6" :key="i" class="skeleton h-12 rounded-lg" />
      </div>

      <div v-else-if="filtered.length === 0" class="flex flex-col items-center justify-center px-4 py-16 text-center">
        <div class="grid size-10 place-items-center rounded-full bg-slate-100 text-slate-400"><Inbox class="size-5" /></div>
        <p class="mt-3 text-sm font-medium text-slate-700">没有匹配的收藏</p>
        <p class="mt-1 text-xs text-slate-400">{{ query ? '换个关键词试试' : 'App 同步后这里会出现内容' }}</p>
      </div>

      <div v-else class="overflow-x-auto">
        <table class="w-full min-w-[820px] text-left text-xs">
          <thead class="border-b border-slate-100 bg-slate-50/70 text-[10px] uppercase tracking-[0.12em] text-slate-400">
            <tr>
              <th class="px-4 py-3 font-semibold">收藏</th>
              <th class="px-4 py-3 font-semibold">内容</th>
              <th class="px-4 py-3 font-semibold">备份</th>
              <th class="px-4 py-3 font-semibold">更新</th>
              <th class="px-4 py-3 font-semibold"></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr
              v-for="row in filtered"
              :key="row.url"
              class="cursor-pointer transition-colors hover:bg-slate-50/80"
              @click="selected = row"
            >
              <td class="max-w-[420px] px-4 py-3">
                <p class="truncate font-medium text-slate-800" :title="row.url">{{ row.note || row.url }}</p>
                <p class="mt-1 truncate font-mono text-[10px] text-slate-400">{{ row.url }}</p>
              </td>
              <td class="px-4 py-3">
                <StatusBadge :state="row.media_state" />
                <span class="ml-2 text-[10px] text-slate-400">{{ (row.media?.segments ?? []).length }} 段</span>
              </td>
              <td class="px-4 py-3">
                <StatusBadge :state="backupSummary(row).state" :label="backupSummary(row).label" dot />
              </td>
              <td class="px-4 py-3 text-slate-500">{{ formatTime(row.updated_at) }}</td>
              <td class="px-4 py-3 text-right">
                <ChevronRight class="ml-auto size-4 text-slate-300" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="hasMore" class="border-t border-slate-100 px-4 py-3 text-center">
        <button
          class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-600 transition hover:border-slate-300 disabled:opacity-50"
          :disabled="loadingMore"
          @click="load(true)"
        >
          {{ loadingMore ? '加载中…' : '加载更多' }}
        </button>
      </div>
    </section>

    <MediaDetailDrawer v-if="selected" :item="selected" :media-base="mediaBase" @close="selected = null" />
  </div>
</template>