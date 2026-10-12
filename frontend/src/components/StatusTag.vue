<script setup lang="ts">
defineProps<{status: string}>()
const names: Record<string, string> = {CREATED:'待提交',VALIDATING:'输入校验',READY:'已就绪',QUEUED:'排队中',WAITING_RESOURCE:'资源等待',SUBMITTING:'提交中',SUBMITTED:'执行已接受',RUNNING:'求解中',POST_PROCESSING:'结果处理',REPORTING:'报告生成',SUCCESS:'完成',VALIDATION_FAILED:'校验失败',SUBMIT_FAILED:'提交失败',RUNTIME_FAILED:'求解失败',POST_PROCESS_FAILED:'结果处理失败',REPORT_FAILED:'报告失败',UNKNOWN:'待确认',CANCEL_REQUESTED:'取消确认中',CANCELLED:'已取消',DRAFT:'草稿',REVIEWED:'已审核',APPROVED:'已批准'}
const color = (value: string): 'success' | 'danger' | 'warning' | 'info' | 'primary' => value === 'SUCCESS' || value === 'APPROVED' ? 'success' : value.endsWith('FAILED') ? 'danger' : ['UNKNOWN','WAITING_RESOURCE','CANCEL_REQUESTED'].includes(value) ? 'warning' : ['CREATED','CANCELLED','DRAFT'].includes(value) ? 'info' : 'primary'
</script>
<template>
  <el-tag :type="color(status)" effect="light" :title="status">
    {{ names[status] ?? status }}
  </el-tag>
</template>
