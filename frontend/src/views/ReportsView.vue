<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, download, errorText, time } from '../api/client'
import type {Comparison, Job, Page, Project, Report} from '../api/types'
import { useSessionStore } from '../stores/session'
import StatusTag from '../components/StatusTag.vue'
const templates=ref<{code:string;version:string;analysisGroup:string;enabled:boolean}[]>([]);
async function templateToggle(code:string,enabled:boolean){try{templates.value=(await api.patch('/report-templates/'+code,{enabled})).data;ElMessage.success('模板状态已更新。')}catch(failure){ElMessage.error(errorText(failure))}}
const session=useSessionStore();const projects=ref<Project[]>([]);const project=ref<number>();const reports=ref<Report[]>([]);const jobs=ref<Job[]>([]);const selectedJob=ref('');const baseline=ref('');const parser=ref('v1');const comparisons=ref<Comparison[]>([]);const candidates=ref<Job[]>([]);const dialog=ref(false);const busy=ref(false);const error=ref('');let key=crypto.randomUUID();let previous=''
async function load(){busy.value=true;try{templates.value=(await api.get('/report-templates')).data;reports.value=(await api.get<Report[]>('/reports',{params:{projectId:project.value}})).data;jobs.value=(await api.get<Page<Job>>('/jobs',{params:{projectId:project.value,status:'SUCCESS',size:100}})).data.items;error.value=''}catch(failure){error.value=errorText(failure)}finally{busy.value=false}}
async function choose() {
 baseline.value=''; comparisons.value=[]; candidates.value=[]; if(!selectedJob.value)return
 try { candidates.value=(await api.get<{jobNo:string;jobId:number}[]>(`/jobs/${selectedJob.value}/baselines`,{params:{parserVersion:parser.value}})).data.map(row=>({jobNo:row.jobNo,id:row.jobId}) as Job); await compare() } catch(failure) {error.value=errorText(failure)}
}
async function compare(){if(!selectedJob.value)return;try{const response=await api.get<{comparisons:Comparison[]}>(`/jobs/${selectedJob.value}/comparison`,{params:{parserVersion:parser.value,baselineJobNo:baseline.value||undefined}});comparisons.value=response.data.comparisons}catch(failure){error.value=errorText(failure)}}
async function generate(){busy.value=true;const body={parserVersion:parser.value,baselineJobNo:baseline.value||undefined};const encoded=JSON.stringify({...body,job:selectedJob.value});if(previous&&previous!==encoded)key=crypto.randomUUID();previous=encoded;try{await api.post(`/jobs/${selectedJob.value}/reports`,{...body,idempotencyKey:key});dialog.value=false;key=crypto.randomUUID();ElMessage.success('新草稿已请求，生成后会出现在版本列表。');await load()}catch(failure){ElMessage.error(errorText(failure))}finally{busy.value=false}}
async function review(report:Report,approve:boolean){try{await api.post(`/reports/${report.id}/${approve?'approve':'review'}`);await load();ElMessage.success(approve?'已人工批准该版本。':'已审核该草稿。')}catch(failure){ElMessage.error(errorText(failure))}}
async function getFile(report:Report){try{await download(report.downloadUrl,`${report.jobNo}_${report.parserVersion}_report-v${report.reportVersion}.pptx`)}catch(failure){ElMessage.error(errorText(failure))}}
onMounted(async()=>{try{projects.value=(await api.get<Project[]>('/projects')).data;await load()}catch(failure){error.value=errorText(failure)}})
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">
          REPORT LIBRARY
        </p><h1>报告中心</h1><p class="muted">
          管理草稿、历史版本与人工审核，工程数字来自已确认结果。
        </p>
      </div><div class="actions">
        <el-select v-model="project" placeholder="全部项目" clearable @change="load">
          <el-option v-for="row in projects" :key="row.id" :label="row.name" :value="row.id" />
        </el-select><el-button type="primary" @click="dialog=true">
          生成草稿
        </el-button>
      </div>
    </div><el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="space-bottom" /><el-card shadow="never">
      <div class="panel-heading">
        <h2>报告版本 <span class="muted">{{ reports.length }}</span></h2><el-button text :loading="busy" @click="load">
          刷新
        </el-button>
      </div><el-table v-loading="busy" :data="reports" empty-text="尚无报告，完成任务后将自动生成草稿">
        <el-table-column label="任务" min-width="225">
          <template #default="scope">
            <router-link :to="'/jobs/'+scope.row.jobNo" class="mono link">
              {{ scope.row.jobNo }}
            </router-link>
          </template>
        </el-table-column><el-table-column prop="conditionCode" label="工况" min-width="150" /><el-table-column label="版本" min-width="150">
          <template #default="scope">
            {{ scope.row.parserVersion }} / Report {{ scope.row.reportVersion }}<small class="muted block">Subcase {{ scope.row.context.subcase }}</small>
          </template>
        </el-table-column><el-table-column label="审核状态" width="120">
          <template #default="scope">
            <StatusTag :status="scope.row.reportStatus" />
          </template>
        </el-table-column><el-table-column prop="baselineJobNo" label="基线" min-width="225" /><el-table-column label="生成时间" min-width="180">
          <template #default="scope">
            {{ time(scope.row.createdAt) }}
          </template>
        </el-table-column><el-table-column label="操作" min-width="230">
          <template #default="scope">
            <el-button text type="primary" @click="getFile(scope.row)">
              下载 PPTX
            </el-button><el-button v-if="session.lead()&&scope.row.reportStatus==='DRAFT'" text @click="review(scope.row,false)">
              Review
            </el-button><el-button v-if="session.admin()&&scope.row.reportStatus==='REVIEWED'" text type="success" @click="review(scope.row,true)">
              Approve
            </el-button>
          </template>
        </el-table-column>
      </el-table><p class="muted small">
        DRAFT → REVIEWED → APPROVED 为人工流程，新版本不会继承旧版本批准状态。
      </p>
    </el-card><el-card v-if="session.admin()" shadow="never" class="space-top">
      <h2>报告模板</h2><el-table :data="templates">
        <el-table-column prop="code" label="模板" /><el-table-column prop="version" label="版本" /><el-table-column prop="analysisGroup" label="适用学科" /><el-table-column label="启用">
          <template #default="scope">
            <el-switch :model-value="Boolean(scope.row.enabled)" @change="(value: boolean|string|number)=>templateToggle(scope.row.code,Boolean(value))" />
          </template>
        </el-table-column>
      </el-table><p class="muted small">
        模板停用后阻止新报告生成，历史报告可继续下载。
      </p>
    </el-card><el-dialog v-model="dialog" title="生成新报告草稿" width="min(850px, 94vw)">
      <el-form label-position="top">
        <el-form-item label="已完成任务">
          <el-select v-model="selectedJob" filterable class="full" @change="choose">
            <el-option v-for="row in jobs" :key="row.id" :label="row.jobNo+' · '+row.conditionCode" :value="row.jobNo" />
          </el-select>
        </el-form-item><div class="form-row">
          <el-form-item label="解析版本">
            <el-select v-model="parser" @change="choose">
              <el-option label="v1" value="v1" /><el-option label="v2" value="v2" />
            </el-select>
          </el-form-item><el-form-item v-if="session.lead()" label="同工况基线">
            <el-select v-model="baseline" clearable filterable placeholder="自动选择历史基线" @change="compare">
              <el-option v-for="row in candidates" :key="row.id" :label="row.jobNo" :value="row.jobNo" />
            </el-select>
          </el-form-item>
        </div>
      </el-form><el-table :data="comparisons" empty-text="选择任务查看后端比较值">
        <el-table-column prop="metricCode" label="指标" min-width="210" /><el-table-column label="基线">
          <template #default="scope">
            {{ scope.row.baseline ?? 'N/A' }}
          </template>
        </el-table-column><el-table-column prop="current" label="当前" /><el-table-column label="差值">
          <template #default="scope">
            {{ scope.row.delta ?? 'N/A' }}
          </template>
        </el-table-column><el-table-column label="差值 %">
          <template #default="scope">
            {{ scope.row.deltaPercent ?? 'N/A' }}
          </template>
        </el-table-column><el-table-column prop="unit" label="单位" />
      </el-table><template #footer>
        <el-button @click="dialog=false">
          返回
        </el-button><el-button type="primary" :loading="busy" :disabled="!selectedJob" @click="generate">
          生成 DRAFT
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
