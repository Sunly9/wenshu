import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'kb-list', component: () => import('../views/KbListView.vue') },
    { path: '/kb/:id', name: 'kb-detail', component: () => import('../views/KbDetailView.vue') },
    { path: '/chat/:kbId', name: 'chat', component: () => import('../views/ChatView.vue') },
    { path: '/join/:code', name: 'join', component: () => import('../views/JoinView.vue') },
  ],
})

export default router
