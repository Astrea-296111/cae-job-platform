<script setup lang="ts">
import { ElCard, ElTag } from 'element-plus'

const capabilities = [
  '统一工程计算任务与状态跟踪',
  'Mock 计算节点与资源调度',
  '结果指标、标准报告与版本对比',
]
</script>

<template>
  <section>
    <p class="eyebrow">
      CAE COMPUTE CONTROL PLANE
    </p>
    <h1>工程仿真，持续可追踪。</h1>
    <p class="lead">
      开发环境前端已启动。平台使用公开 Synthetic 数据与 Mock Solver，服务工程计算、结果管理及报告流程。
    </p>
    <el-card shadow="never" class="bootstrap-card">
      <template #header>
        <div class="card-heading">
          <h2>Repository Bootstrap</h2>
          <el-tag type="success">
            前端已就绪
          </el-tag>
        </div>
      </template>
      <p>Vue 3、TypeScript、路由与组件库已加载。</p>
      <ul class="capability-list">
        <li v-for="capability in capabilities" :key="capability">
          {{ capability }}
        </li>
      </ul>
      <p class="muted">
        业务功能将按工程规范逐个里程碑实现并验收。
      </p>
    </el-card>
  </section>
</template>
