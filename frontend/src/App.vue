<script setup lang="ts">
import { ElTag } from 'element-plus'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <router-link to="/" class="brand">
        <span class="brand-mark">CAE</span>
        <span>仿真任务与结果平台</span>
      </router-link>
      <el-tag type="info" effect="plain">
        公开复现 · Mock Solver
      </el-tag>
    </header>
    <main class="page-content">
      <router-view />
    </main>
  </div>
</template>
