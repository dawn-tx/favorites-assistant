import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import App from './App.vue';
import Overview from './views/Overview.vue';
import Favorites from './views/Favorites.vue';
import Jobs from './views/Jobs.vue';
import './style.css';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: Overview, meta: { title: '概览', eyebrow: 'Operations' } },
    { path: '/favorites', name: 'favorites', component: Favorites, meta: { title: '收藏与媒体', eyebrow: 'Library' } },
    { path: '/jobs', name: 'jobs', component: Jobs, meta: { title: '任务队列', eyebrow: 'Queue' } },
  ],
});

createApp(App).use(router).mount('#app');