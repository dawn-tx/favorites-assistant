<script setup>
import { computed } from 'vue';
import { percent } from '../lib/format';

const props = defineProps({
  label: { type: String, required: true },
  value: { type: Number, default: 0 },
  total: { type: Number, default: 0 },
  tone: { type: String, default: 'indigo' },
});

const width = computed(() => `${percent(props.value, props.total)}%`);
const barTone = computed(() => ({
  indigo: 'bg-indigo-500',
  emerald: 'bg-emerald-500',
  amber: 'bg-amber-500',
  rose: 'bg-rose-500',
  slate: 'bg-slate-400',
}[props.tone] || 'bg-indigo-500'));
</script>

<template>
  <div>
    <div class="mb-2 flex items-center justify-between gap-3 text-xs">
      <span class="text-slate-600">{{ label }}</span>
      <span class="tabular-nums text-slate-400">{{ value }} / {{ total }}</span>
    </div>
    <div class="h-1.5 overflow-hidden rounded-full bg-slate-100">
      <div class="h-full rounded-full transition-all" :class="barTone" :style="{ width }" />
    </div>
  </div>
</template>