<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { errorText } from '../api/client'
const router = useRouter(); const route = useRoute(); const session = useSessionStore()
const name = ref('engineer'); const password = ref(''); const busy = ref(false); const error = ref('')
async function login() { if (busy.value) return; busy.value = true; error.value = ''; try { await session.login(name.value, password.value); const next = String(route.query.next ?? '/'); await router.replace(next.startsWith('/') && !next.startsWith('//') ? next : '/') } catch (failure) { error.value = errorText(failure) } finally { busy.value = false } }
</script>
<template>
  <section class="login-layout">
    <div class="login-story">
      <p class="eyebrow">
        ENGINEERING WORKSPACE
      </p><h1>从求解任务<br>到可审阅的结果。</h1><p class="lead">
        统一追踪仿真执行、工程指标、图片与报告版本。
      </p><div class="story-steps">
        <span>01 输入与工况</span><span>02 计算与结果</span><span>03 报告与审核</span>
      </div><p class="muted">
        公开案例 · Synthetic 数据 · 独立 Mock Solver
      </p>
    </div>
    <el-card class="login-card" shadow="never">
      <h2>登录工作台</h2><p class="muted">
        使用演示账户体验完整工程流程。
      </p><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><el-form label-position="top" @submit.prevent="login">
        <el-form-item label="用户名">
          <el-input v-model="name" autocomplete="username" />
        </el-form-item><el-form-item label="密码">
          <el-input v-model="password" type="password" show-password autocomplete="current-password" @keyup.enter="login" />
        </el-form-item><el-button type="primary" :loading="busy" native-type="submit" class="full">
          登录
        </el-button>
      </el-form><div class="demo-users">
        <strong>演示账户</strong><button v-for="user in ['engineer','lead','admin']" :key="user" @click="name=user; password=user+'123'">
          {{ user }} / {{ user }}123
        </button>
      </div>
    </el-card>
  </section>
</template>
