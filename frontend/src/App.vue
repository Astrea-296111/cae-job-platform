<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useSessionStore } from './stores/session'
const router = useRouter(); const route = useRoute(); const session = useSessionStore()
const links = [{to:'/',label:'总览',icon:'◈'},{to:'/jobs',label:'仿真任务',icon:'▤'},{to:'/compute-nodes',label:'计算资源',icon:'▦'},{to:'/batches',label:'批量仿真',icon:'▥'},{to:'/reports',label:'报告中心',icon:'▧'}]
function logout() { session.logout(); void router.replace('/login') }
onMounted(() => window.addEventListener('cae-session-expired', logout))
onUnmounted(() => window.removeEventListener('cae-session-expired', logout))
</script>
<template>
  <div v-if="route.path === '/login'" class="login-shell">
    <div class="login-brand">
      <b>CAE</b><span>仿真工作台</span>
    </div><router-view />
  </div>
  <div v-else class="workspace">
    <aside class="sidebar">
      <router-link to="/" class="brand">
        <span class="brand-mark">CAE</span><span>仿真工作台<small>SIMULATION LAB</small></span>
      </router-link><p class="nav-caption">
        工程空间
      </p><nav>
        <router-link v-for="link in links" :key="link.to" :to="link.to" :class="{active: link.to === '/' ? route.path === '/' : route.path.startsWith(link.to)}">
          <i aria-hidden="true">{{ link.icon }}</i>{{ link.label }}
        </router-link>
      </nav><div class="sidebar-note">
        <span class="live-dot"></span>公开演示环境<small>Synthetic / Mock Solver</small>
      </div>
    </aside><div class="workspace-main">
      <header class="topbar">
        <span class="breadcrumb">工程计算 <span>/</span> 任务与结果</span><div class="topbar-user">
          <el-tag size="small" type="info">
            {{ session.roles.join(' · ') }}
          </el-tag><strong>{{ session.username }}</strong><el-button text @click="logout">
            退出
          </el-button>
        </div>
      </header><main class="page-content">
        <router-view :key="route.path" />
      </main><footer class="page-footer">
        CAE 仿真工作台 <span>时间显示：Asia/Shanghai · 报告默认生成草稿</span>
      </footer>
    </div>
  </div>
</template>
