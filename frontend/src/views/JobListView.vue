<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorText, time } from '../api/client'
import type {Job, Page, Project} from '../api/types'
import StatusTag from '../components/StatusTag.vue'
const route = useRoute(); const router = useRouter(); const projects = ref<Project[]>([]); const rows = ref<Job[]>([]); const total = ref(0); const busy = ref(false); const error = ref(''); const page = ref(Number(route.query.page ?? 0)); const size = ref(20)
const filters = ref({projectId: String(route.query.projectId ?? ''), analysisType: String(route.query.analysisType ?? ''), status: String(route.query.status ?? ''), conditionCode: String(route.query.conditionCode ?? ''), submitter: String(route.query.submitter ?? ''), dateFrom: String(route.query.dateFrom ?? ''), dateTo: String(route.query.dateTo ?? '')})
const statuses = ['CREATED','VALIDATING','READY','QUEUED','WAITING_RESOURCE','SUBMITTING','SUBMITTED','RUNNING','POST_PROCESSING','REPORTING','SUCCESS','VALIDATION_FAILED','SUBMIT_FAILED','RUNTIME_FAILED','POST_PROCESS_FAILED','REPORT_FAILED','UNKNOWN','CANCEL_REQUESTED','CANCELLED']
let controller: AbortController | undefined
async function load(reset = false) { if (reset) page.value = 0; controller?.abort(); controller = new AbortController(); const requestController = controller; const params = Object.fromEntries(Object.entries({...filters.value, page: page.value, size: size.value, timeZone: 'Asia/Shanghai'}).filter(([,value]) => value !== '')); busy.value = true; error.value = ''; try { const response = await api.get<Page<Job>>('/jobs', {params, signal: requestController.signal}); if(requestController.signal.aborted)return;rows.value = response.data.items; total.value = response.data.total; await router.replace({query: Object.fromEntries(Object.entries(params).map(([key,value])=>[key,String(value)]))}) } catch (failure) { if (!requestController.signal.aborted) error.value = errorText(failure) } finally { if(controller===requestController)busy.value = false } }
function onPage(value: number) { page.value=value-1; void load() }
onMounted(async () => { try { projects.value = (await api.get<Project[]>('/projects')).data; await load() } catch (failure) { error.value = errorText(failure) } })
onUnmounted(()=>controller?.abort())
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">
          SIMULATION JOBS
        </p><h1>仿真任务</h1><p class="muted">
          从提交到报告，每次执行保留完整状态与结果。
        </p>
      </div><router-link to="/jobs/new">
        <el-button type="primary">
          ＋ 创建任务
        </el-button>
      </router-link>
    </div><el-card shadow="never" class="space-bottom">
      <div class="filter-grid">
        <el-select v-model="filters.projectId" placeholder="全部项目" clearable>
          <el-option v-for="row in projects" :key="row.id" :label="row.name" :value="String(row.id)" />
        </el-select><el-select v-model="filters.analysisType" placeholder="全部学科" clearable>
          <el-option v-for="type in ['STRUCTURE','NVH','CRASH','CFD']" :key="type" :label="type" :value="type" />
        </el-select><el-select v-model="filters.status" placeholder="全部状态" clearable>
          <el-option v-for="status in statuses" :key="status" :label="status" :value="status" />
        </el-select><el-input v-model="filters.conditionCode" placeholder="工况代码" clearable /><el-input v-model="filters.submitter" placeholder="提交人" clearable /><el-date-picker v-model="filters.dateFrom" type="date" value-format="YYYY-MM-DD" placeholder="创建日期起" /><el-date-picker v-model="filters.dateTo" type="date" value-format="YYYY-MM-DD" placeholder="创建日期止" /><el-button :loading="busy" @click="load(true)">
          查询
        </el-button>
      </div><p class="muted small">
        日期按 Asia/Shanghai 过滤。
      </p>
    </el-card><el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="space-bottom" /><el-card shadow="never">
      <div class="panel-heading">
        <h2>任务记录 <span class="muted">{{ total }}</span></h2><el-button text :loading="busy" @click="load()">
          刷新
        </el-button>
      </div><el-table v-loading="busy" :data="rows" empty-text="没有符合筛选条件的任务">
        <el-table-column label="任务编号" min-width="225">
          <template #default="scope">
            <router-link :to="'/jobs/'+scope.row.jobNo" class="mono link">
              {{ scope.row.jobNo }}
            </router-link>
          </template>
        </el-table-column><el-table-column prop="analysisType" label="学科" width="115" /><el-table-column prop="conditionCode" label="工况" min-width="150" /><el-table-column label="状态" width="130">
          <template #default="scope">
            <StatusTag :status="scope.row.status" />
          </template>
        </el-table-column><el-table-column prop="stage" label="阶段" min-width="140" /><el-table-column prop="submitterId" label="提交人" width="100" /><el-table-column prop="priority" label="优先级" width="90" /><el-table-column label="创建时间" min-width="190">
          <template #default="scope">
            {{ time(scope.row.createdAt) }}
          </template>
        </el-table-column>
      </el-table><el-pagination v-model:page-size="size" :current-page="page+1" :total="total" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" class="pagination" @current-change="onPage" @size-change="load(true)" />
    </el-card>
  </section>
</template>
