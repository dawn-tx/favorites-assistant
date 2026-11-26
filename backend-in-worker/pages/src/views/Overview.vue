<script setup>
import { computed, onMounted, ref } from 'vue';
import { AlertTriangle, CheckCircle2, Database, Hourglass, RefreshCw, TimerReset } from 'lucide-vue-next';
import KpiCard from '../components/KpiCard.vue';
import StateBar from '../components/StateBar.vue';
import StatusBadge from '../components/StatusBadge.vue';
import { getJson } from '../lib/api';
import { formatRelative, formatTime } from '../lib/format';

const overview = ref(null);
const jobs = ref([]);
const loading = ref(true);
const error = ref('');
const updatedAt = ref(0);

const favoriteTotal = computed(() => overview.value?.favorites?.total ?? 0);
const mediaStates = computed(() => overview.value?.favorites?.media_states ?? {});
const uploadStates = computed(() => overview.value?.favorites?.upload_states ?? {});
const jobsTotal = computed(() => overview.value?.jobs?.total ?? 0);
const jobKinds = computed(() => overview.value?.jobs?.by_kind ?? {});
const ready = computed(() => Number(mediaStates.value.ready ?? 0));
const pending = computed(() => Number(mediaStates.value.pending ?? 0));
const failed = computed(() => Number(mediaStates.value.failed ?? 0));
const uploadFailed = computed(() => Number(uploadStates.value.failed ?? 0) + Number(uploadStates.value.partial ?? 0));

const kindRows = computed(() => ['parse', 'backup', 'upload'].map((kind) => ({
  kind,
  ...(jobKinds.value[kind] ?? { count: 0, locked: 0, oldest_next_at: null }),
})));

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const [overviewData, jobsData] = await Promise.all([
      getJson('/overview'),
      getJson('/jobs?limit=8'),
    ]);
    overview.value = overviewData;
    jobs.value = jobsData.jobs;
    updatedAt.value = Date.now();
  } catch (err) {
    error.value = err.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="space-y-6">
    <section class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p class="text-sm text-slate-500">后台状态、任务积压和媒体备份覆盖情况。</p>
        <p v-if="updatedAt" class="mt-1 text-[11px] text-slate-400">最近刷新 {{ formatTime(updatedAt) }}</p>
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

    <div v-if="error" class="flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
      <AlertTriangle class="mt-0.5 size-4 shrink-0" />
      <div class="flex-1">
        <p class="font-medium">概览加载失败</p>
        <p class="mt-0.5 text-xs text-rose-600">{{ error }}</p>
      </div>
      <button class="text-xs font-medium underline underline-offset-2" @click="load">重试</button>
    </div>

    <section v-if="loading" class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <div v-for="i in 4" :key="i" class="card h-[132px] p-5">
        <div class="skeleton h-2.5 w-20 rounded" />
        <div class="skeleton mt-5 h-8 w-24 rounded" />
        <div class="skeleton mt-4 h-2.5 w-32 rounded" />
      </div>
    </section>

    <template v-else-if="overview">
      <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard label="收藏总数" :value="favoriteTotal" hint="D1 控制库全部收藏">
          <template #icon>
            <div class="grid size-9 place-items-center rounded-lg bg-indigo-50 text-indigo-600"><Database class="size-4" /></div>
          </template>
        </KpiCard>
        <KpiCard label="内容就绪" :value="ready" :hint="`${pending} 条处理中 · ${failed} 条失败`">
          <template #icon>
            <div class="grid size-9 place-items-center rounded-lg bg-emerald-50 text-emerald-600"><CheckCircle2 class="size-4" /></div>
          </template>
        </KpiCard>
        <KpiCard label="待处理任务" :value="jobsTotal" hint="parse / backup / upload 队列">
          <template #icon>
            <div class="grid size-9 place-items-center rounded-lg bg-amber-50 text-amber-600"><Hourglass class="size-4" /></div>
          </template>
        </KpiCard>
        <KpiCard label="上传异常" :value="uploadFailed" hint="failed + partial 收藏">
          <template #icon>
            <div class="grid size-9 place-items-center rounded-lg bg-rose-50 text-rose-600"><TimerReset class="size-4" /></div>
          </template>
        </KpiCard>
      </section>

      <section class="grid gap-4 lg:grid-cols-[1.1fr_0.9fr]">
        <div class="card p-5">
          <div class="mb-5 flex items-center justify-between">
            <div>
              <h2 class="text-sm font-semibold text-slate-900">内容状态</h2>
              <p class="mt-1 text-xs text-slate-500">控制库 favorites 的状态分布</p>
            </div>
            <StatusBadge state="ready" :label="`${ready} 就绪`" dot />
          </div>
          <div class="space-y-4">
            <StateBar label="就绪" :value="ready" :total="favoriteTotal" tone="emerald" />
            <StateBar label="处理中" :value="pending" :total="favoriteTotal" tone="amber" />
            <StateBar label="失败" :value="failed" :total="favoriteTotal" tone="rose" />
          </div>
          <div class="mt-6 grid grid-cols-3 gap-3 border-t border-slate-100 pt-4 text-center">
            <div>
              <p class="text-lg font-semibold tabular-nums text-slate-900">{{ favoriteTotal }}</p>
              <p class="mt-1 text-[11px] text-slate-400">总收藏</p>
            </div>
            <div>
              <p class="text-lg font-semibold tabular-nums text-slate-900">{{ uploadStates.ready ?? 0 }}</p>
              <p class="mt-1 text-[11px] text-slate-400">上传完成</p>
            </div>
            <div>
              <p class="text-lg font-semibold tabular-nums text-slate-900">{{ uploadStates.pending ?? 0 }}</p>
              <p class="mt-1 text-[11px] text-slate-400">待上传</p>
            </div>
          </div>
        </div>

        <div class="card p-5">
          <div class="mb-4">
            <h2 class="text-sm font-semibold text-slate-900">任务队列</h2>
            <p class="mt-1 text-xs text-slate-500">按 kind 聚合，详情见“任务队列”</p>
          </div>
          <div class="space-y-3">
            <div v-for="row in kindRows" :key="row.kind" class="card-muted flex items-center gap-3 px-3.5 py-3">
              <div class="grid size-8 place-items-center rounded-lg bg-white text-xs font-semibold text-slate-500 shadow-sm">
                {{ row.kind.slice(0, 2).toUpperCase() }}
              </div>
              <div class="min-w-0 flex-1">
                <p class="text-xs font-medium text-slate-800">{{ row.kind }}</p>
                <p class="mt-0.5 text-[11px] text-slate-400">
                  {{ row.oldest_next_at ? `最老任务 ${formatRelative(row.oldest_next_at)}` : '暂无任务' }}
                </p>
              </div>
              <div class="text-right">
                <p class="text-sm font-semibold tabular-nums text-slate-900">{{ row.count }}</p>
                <p class="text-[10px] text-slate-400">{{ row.locked ? `${row.locked} 锁定` : '空闲' }}</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="card overflow-hidden">
        <div class="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <h2 class="text-sm font-semibold text-slate-900">最近任务</h2>
            <p class="mt-1 text-xs text-slate-500">按 next_at 升序</p>
          </div>
          <RouterLink :to="{ name: 'jobs' }" class="text-xs font-medium text-indigo-600 hover:text-indigo-700">查看全部</RouterLink>
        </div>
        <div class="overflow-x-auto">
          <table class="w-full min-w-[620px] text-left text-xs">
            <thead class="border-b border-slate-100 bg-slate-50/70 text-[10px] uppercase tracking-[0.12em] text-slate-400">
              <tr>
                <th class="px-5 py-3 font-semibold">Kind</th>
                <th class="px-5 py-3 font-semibold">URL</th>
                <th class="px-5 py-3 font-semibold">Attempts</th>
                <th class="px-5 py-3 font-semibold">Next</th>
                <th class="px-5 py-3 font-semibold">状态</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              <tr v-if="jobs.length === 0">
                <td colspan="5" class="px-5 py-10 text-center text-slate-400">队列为空</td>
              </tr>
              <tr v-for="job in jobs" :key="job.id" class="transition-colors hover:bg-slate-50/70">
                <td class="px-5 py-3"><StatusBadge :state="job.kind" :label="job.kind" /></td>
                <td class="max-w-[360px] truncate px-5 py-3 font-mono text-[11px] text-slate-600">{{ job.url }}</td>
                <td class="px-5 py-3 tabular-nums text-slate-500">{{ job.attempts }}</td>
                <td class="px-5 py-3 text-slate-500">{{ formatRelative(job.next_at) }}</td>
                <td class="px-5 py-3">
                  <StatusBadge :state="job.locked_until > Date.now() ? 'pending' : 'ready'" :label="job.locked_until > Date.now() ? '锁定' : '等待'" />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="rounded-xl border border-slate-200 bg-white/60 px-4 py-3 text-[11px] leading-5 text-slate-500">
        <span class="font-medium text-slate-700">只读视图。</span>
        重试、预热、清缓存等操作会在下一步单独接入；管理 API 只允许在 <code class="rounded bg-slate-100 px-1 py-0.5 text-slate-600">ADMIN_HOST</code> 上访问。
      </section>
    </template>
  </div>
</template>