import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from './stores/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue') },
    { path: '/', component: () => import('./views/DashboardView.vue') },
    { path: '/jobs', component: () => import('./views/JobListView.vue') },
    { path: '/jobs/new', component: () => import('./views/CreateJobView.vue') },
    { path: '/jobs/:jobNo', component: () => import('./views/JobDetailView.vue') },
    { path: '/compute-nodes', component: () => import('./views/NodesView.vue') },
    { path: '/reports', component: () => import('./views/ReportsView.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('./views/NotFoundView.vue') },
  ],
})
router.beforeEach(to => { if (to.path !== '/login' && !useSessionStore().username) return {path: '/login', query: {next: to.fullPath}} })
export default router
