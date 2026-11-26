<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  hint: { type: String, default: '' },
  tone: { type: String, default: 'slate' },
});
</script>

<template>
  <div class="card p-5">
    <div class="flex items-start justify-between gap-4">
      <div>
        <p class="text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">{{ label }}</p>
        <p class="mt-3 text-2xl font-semibold tracking-tight text-slate-950">{{ value }}</p>
      </div>
      <slot name="icon" />
    </div>
    <p v-if="hint" class="mt-3 text-xs text-slate-500">{{ hint }}</p>
  </div>
</template>