<script setup>
import { computed, onMounted, ref } from 'vue';
import { Inbox, RefreshCw, Search } from 'lucide-vue-next';
import StatusBadge from '../components/StatusBadge.vue';
import { getJson } from '../lib/api';
import { formatFullTime, formatRelative } from '../lib/format';

const jobs = ref([]);
const loading = ref(true);
const error = ref('');
const query = ref('');
const now = ref(Date.now());

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  if (!keyword) return jobs.value;
  return jobs.value.filter((job) =>
    [job.url, job.kind, String(job.attempts)].some((value) => String(value ?? '').toLowerCase().includes(keyword)),
  );
});

const counts = computed(() => {
  const out = {};
  for (const job of jobs.value) out[job.kind] = (out[job.kind] ?? 0) + 1;
  return out;
});

function jobState(job) {
  if (job.locked_until > now.value) return { state: 'pending', label: '已锁定' };
  if (job.next_at > now.value) return { state: 'queued', label: '等待' };
  return { state: 'ready', label: '可领取' };
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const data = await getJson('/jobs?limit=200');
    jobs.value = data.jobs;
    now.value = Date.now();
  } catch (err) {
    error.value = err.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="space-y-5">
    <section class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p class="text-sm text-slate-500">D1 jobs 队列表；只读展示 next_at、锁和重试次数。</p>
        <p class="mt-1 text-[11px] text-slate-400">当前 {{ jobs.length }} 条</p>
      </div>
      <button
        class="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-medium text-slate-600 shadow-sm transition hover:border-slate-300 hover:text-slate-900 disabled:opacity-50"
        :disabled="loading"
        @click="load"
      >
        <RefreshCw class="size-3.5" :class="loading ? 'animate-spin' : ''" />
        刷新
      </button>
    </section>

    <section class="grid gap-3 sm:grid-cols-3">
      <div v-for="kind in ['parse', 'backup', 'upload']" :key="kind" class="card-muted px-4 py-3">
        <p class="text-[10px] uppercase tracking-[0.14em] text-slate-400">{{ kind }}</p>
        <p class="mt-2 text-xl font-semibold tabular-nums text-slate-900">{{ counts[kind] ?? 0 }}</p>
      </div>
    </section>

    <section class="card overflow-hidden">
      <div class="flex flex-wrap items-center gap-3 border-b border-slate-100 px-4 py-3">
        <div class="relative min-w-[220px] flex-1">
          <Search class="pointer-events-none absolute left-3 top-1/2 size-3.5 -translate-y-1/2 text-slate-400" />
          <input
            v-model="query"
            type="search"
            placeholder="搜索 URL、kind 或 attempts"
            class="h-9 w-full rounded-lg border border-slate-200 bg-slate-50/70 pl-9 pr-3 text-xs text-slate-700 outline-none transition placeholder:text-slate-400 focus:border-indigo-300 focus:bg-white focus:ring-2 focus:ring-indigo-100"
          />
        </div>
        <span class="text-[11px] text-slate-400">显示 {{ filtered.length }} 条</span>
      </div>

      <div v-if="error" class="border-b border-rose-100 bg-rose-50 px-4 py-3 text-xs text-rose-700">
        {{ error }}
        <button class="ml-2 font-medium underline underline-offset-2" @click="load">重试</button>
      </div>

      <div v-if="loading" class="space-y-2 p-4">
        <div v-for="i in 8" :key="i" class="skeleton h-10 rounded-lg" />
      </div>

      <div v-else-if="filtered.length === 0" class="flex flex-col items-center justify-center px-4 py-16 text-center">
        <div class="grid size-10 place-items-center rounded-full bg-slate-100 text-slate-400"><Inbox class="size-5" /></div>
        <p class="mt-3 text-sm font-medium text-slate-700">队列为空</p>
        <p class="mt-1 text-xs text-slate-400">解析、备份和上传任务会在这里出现</p>
      </div>

      <div v-else class="overflow-x-auto">
        <table class="w-full min-w-[860px] text-left text-xs">
          <thead class="border-b border-slate-100 bg-slate-50/70 text-[10px] uppercase tracking-[0.12em] text-slate-400">
            <tr>
              <th class="px-4 py-3 font-semibold">ID</th>
              <th class="px-4 py-3 font-semibold">Kind</th>
              <th class="px-4 py-3 font-semibold">URL</th>
              <th class="px-4 py-3 font-semibold">Attempts</th>
              <th class="px-4 py-3 font-semibold">Next</th>
              <th class="px-4 py-3 font-semibold">状态</th>
              <th class="px-4 py-3 font-semibold">Lock</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr v-for="job in filtered" :key="job.id" class="transition-colors hover:bg-slate-50/70">
              <td class="px-4 py-3 font-mono text-[10px] text-slate-400">#{{ job.id }}</td>
              <td class="px-4 py-3"><StatusBadge :state="job.kind" :label="job.kind" /></td>
              <td class="max-w-[360px] truncate px-4 py-3 font-mono text-[10px] text-slate-600" :title="job.url">{{ job.url }}</td>
              <td class="px-4 py-3 tabular-nums" :class="job.attempts > 0 ? 'text-amber-600' : 'text-slate-500'">{{ job.attempts }}</td>
              <td class="px-4 py-3 text-slate-500" :title="formatFullTime(job.next_at)">{{ formatRelative(job.next_at, now) }}</td>
              <td class="px-4 py-3"><StatusBadge :state="jobState(job).state" :label="jobState(job).label" /></td>
              <td class="px-4 py-3 text-[10px] text-slate-400">{{ job.locked_until > now ? formatRelative(job.locked_until, now) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>