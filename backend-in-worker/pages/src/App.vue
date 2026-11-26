<script setup>
import { computed, ref } from 'vue';
import { RouterLink, RouterView, useRoute } from 'vue-router';
import { Activity, LayoutDashboard, Library, ListChecks, Menu, ShieldCheck, X } from 'lucide-vue-next';

const route = useRoute();
const sidebarOpen = ref(false);

const navItems = [
  { name: 'overview', label: '概览', icon: LayoutDashboard, hint: 'Overview' },
  { name: 'favorites', label: '收藏与媒体', icon: Library, hint: 'Library' },
  { name: 'jobs', label: '任务队列', icon: ListChecks, hint: 'Queue' },
];

const title = computed(() => route.meta.title || '管理台');
const eyebrow = computed(() => route.meta.eyebrow || 'Favorites');
</script>

<template>
  <div class="min-h-screen bg-[#f6f7f9]">
    <div
      v-if="sidebarOpen"
      class="fixed inset-0 z-30 bg-slate-950/40 backdrop-blur-sm md:hidden"
      @click="sidebarOpen = false"
    />

    <aside
      class="fixed inset-y-0 left-0 z-40 flex w-[248px] flex-col border-r border-white/8 bg-[#0b1120] text-slate-300 transition-transform duration-200 md:translate-x-0"
      :class="sidebarOpen ? 'translate-x-0' : '-translate-x-full'"
    >
      <div class="flex h-16 items-center gap-3 border-b border-white/8 px-5">
        <div class="grid size-8 place-items-center rounded-lg bg-indigo-500 text-sm font-bold text-white shadow-lg shadow-indigo-950/30">
          F
        </div>
        <div class="min-w-0">
          <p class="truncate text-sm font-semibold tracking-wide text-white">Favorites</p>
          <p class="text-[10px] uppercase tracking-[0.18em] text-slate-500">Admin Console</p>
        </div>
        <button class="ml-auto rounded-md p-1.5 text-slate-400 hover:bg-white/8 hover:text-white md:hidden" @click="sidebarOpen = false">
          <X class="size-4" />
        </button>
      </div>

      <nav class="flex-1 space-y-1 px-3 py-4">
        <p class="px-2 pb-2 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-600">Workspace</p>
        <RouterLink
          v-for="item in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          class="group flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors"
          :class="route.name === item.name
            ? 'bg-white/10 text-white shadow-sm'
            : 'text-slate-400 hover:bg-white/6 hover:text-slate-100'"
          @click="sidebarOpen = false"
        >
          <component :is="item.icon" class="size-4 shrink-0" :class="route.name === item.name ? 'text-indigo-300' : 'text-slate-500 group-hover:text-slate-300'" />
          <span class="flex-1">{{ item.label }}</span>
          <span class="text-[9px] uppercase tracking-wider text-slate-600">{{ item.hint }}</span>
        </RouterLink>
      </nav>

      <div class="border-t border-white/8 p-4">
        <div class="rounded-xl border border-white/8 bg-white/[0.03] p-3">
          <div class="flex items-center gap-2 text-xs font-medium text-slate-200">
            <span class="relative flex size-2">
              <span class="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-50" />
              <span class="relative inline-flex size-2 rounded-full bg-emerald-400" />
            </span>
            Read-only console
          </div>
          <p class="mt-1.5 text-[11px] leading-4 text-slate-500">D1 · Workers · External storage</p>
        </div>
        <div class="mt-3 flex items-center gap-2 px-1 text-[10px] text-slate-600">
          <ShieldCheck class="size-3.5" />
          Protected by Cloudflare Access
        </div>
      </div>
    </aside>

    <div class="md:pl-[248px]">
      <header class="sticky top-0 z-20 flex h-16 items-center gap-3 border-b border-slate-200/80 bg-[#f6f7f9]/90 px-4 backdrop-blur md:px-8">
        <button class="rounded-lg p-2 text-slate-500 hover:bg-white hover:text-slate-900 md:hidden" @click="sidebarOpen = true">
          <Menu class="size-5" />
        </button>
        <div class="min-w-0">
          <div class="flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-400">
            <Activity class="size-3" />
            {{ eyebrow }}
          </div>
          <h1 class="truncate text-lg font-semibold tracking-tight text-slate-950">{{ title }}</h1>
        </div>
        <div class="ml-auto hidden items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-1.5 text-[11px] text-slate-500 shadow-sm sm:flex">
          <span class="size-1.5 rounded-full bg-emerald-500" />
          Production
        </div>
      </header>

      <main class="mx-auto max-w-[1500px] px-4 py-6 md:px-8 md:py-8">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>