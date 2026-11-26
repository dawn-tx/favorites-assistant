<script setup>
import { computed } from 'vue';
import { stateLabel } from '../lib/format';

const props = defineProps({
  state: { type: String, default: 'unknown' },
  label: { type: String, default: '' },
  dot: { type: Boolean, default: false },
});

const tone = computed(() => {
  const state = props.state;
  if (['ready', 'hit', 'ok', 'success'].includes(state)) return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  if (['pending', 'queued', 'waiting', 'miss', 'expired', 'updating'].includes(state)) return 'border-amber-200 bg-amber-50 text-amber-700';
  if (['failed', 'error', 'offline'].includes(state)) return 'border-rose-200 bg-rose-50 text-rose-700';
  if (['partial', 'warn', 'stale'].includes(state)) return 'border-orange-200 bg-orange-50 text-orange-700';
  return 'border-slate-200 bg-slate-50 text-slate-600';
});

const dotTone = computed(() => {
  const state = props.state;
  if (['ready', 'hit', 'ok', 'success'].includes(state)) return 'bg-emerald-500';
  if (['pending', 'queued', 'waiting', 'miss', 'expired', 'updating'].includes(state)) return 'bg-amber-500';
  if (['failed', 'error', 'offline'].includes(state)) return 'bg-rose-500';
  if (['partial', 'warn', 'stale'].includes(state)) return 'bg-orange-500';
  return 'bg-slate-400';
});

const text = computed(() => props.label || stateLabel(props.state));
</script>

<template>
  <span class="inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] font-medium" :class="tone">
    <span v-if="dot" class="size-1.5 rounded-full" :class="dotTone" />
    {{ text }}
  </span>
</template>