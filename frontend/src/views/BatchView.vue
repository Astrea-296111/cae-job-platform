<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {api,errorText,time} from '../api/client'
import type {Batch,Case,Project,Profile,Revision} from '../api/types'
import StatusTag from '../components/StatusTag.vue'
const number=String(useRoute().params.batchNo??'');const router=useRouter();const rows=ref<Batch[]>([]);const detail=ref<Batch>();const error=ref('');const busy=ref(false);const dialog=ref(false)
const projects=ref<Project[]>([]);const revisions=ref<Revision[]>([]);const cases=ref<Case[]>([]);const profiles=ref<Profile[]>([]);const project=ref<number>();const selected=ref<string[]>([]);const copies=ref(24);const profile=ref('SMALL');const name=ref('公开案例批量仿真')
const eligible=computed(()=>cases.value.filter(row=>revisions.value.some(rev=>rev.revision===row.modelRevision)));const total=computed(()=>selected.value.length*copies.value)
let key=crypto.randomUUID();let previous='';let timer:ReturnType<typeof setInterval>;let inflight=false;let alive=true;let revisionSequence=0
async function load(){if(inflight)return;inflight=true;try{if(number)detail.value=(await api.get<Batch>('/batches/'+number)).data;else rows.value=(await api.get<Batch[]>('/batches',{params:{projectId:project.value}})).data;error.value=''}catch(failure){if(alive)error.value=errorText(failure)}finally{inflight=false}}
watch(project,async()=>{selected.value=[];const sequence=++revisionSequence;if(project.value){try{const response=await api.get<Revision[]>('/model-revisions',{params:{projectId:project.value}});if(sequence===revisionSequence)revisions.value=response.data;void load()}catch(failure){error.value=errorText(failure)}}})
async function create(){if(busy.value||!project.value||total.value<1||total.value>200)return;const jobs=selected.value.flatMap(path=>{const entry=cases.value.find(row=>row.casePath===path)!;const revision=revisions.value.find(row=>row.revision===entry.modelRevision)!;return Array.from({length:copies.value},()=>({projectId:project.value,modelRevisionId:revision.id,analysisType:entry.analysisType,conditionCode:entry.conditionCode,casePath:path,resourceProfile:profile.value}))});const body={name:name.value,projectId:project.value,jobs};const encoded=JSON.stringify(body);if(previous&&previous!==encoded)key=crypto.randomUUID();previous=encoded;busy.value=true;try{const response=await api.post<Batch>('/batches',{...body,idempotencyKey:key});dialog.value=false;await router.push('/batches/'+response.data.batchNo)}catch(failure){error.value=errorText(failure)}finally{busy.value=false}}
onMounted(async()=>{try{const result=await Promise.all([api.get<Project[]>('/projects'),api.get<Case[]>('/cases'),api.get<Profile[]>('/resource-profiles')]);projects.value=result[0].data;cases.value=result[1].data;profiles.value=result[2].data;project.value=projects.value[0]?.id;await load();timer=setInterval(()=>{if(!document.hidden)void load()},number?2000:5000)}catch(failure){error.value=errorText(failure)}})
onUnmounted(()=>{alive=false;clearInterval(timer)})
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">
          BATCH SIMULATIONS
        </p><h1>{{ detail?.name??'批量仿真' }}</h1><p class="muted">
          逐步启动任务，资源可用时继续执行；单个失败保留原因，其余任务继续。
        </p>
      </div><div class="actions">
        <el-button @click="load">
          刷新
        </el-button><router-link v-if="number" to="/batches">
          所有批次
        </router-link><el-button v-else type="primary" @click="dialog=true">
          创建批次
        </el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="space-bottom" />
    <template v-if="detail">
      <div class="job-summary">
        <code>{{ detail.batchNo }}</code><StatusTag :status="detail.status" /><span>创建 {{ time(detail.createdAt) }}</span><span>已建立 {{ detail.createdChildren }} / {{ detail.counts.total }} 个任务</span>
      </div><el-alert v-if="detail.backpressure.paused" title="当前队列繁忙，待接入任务会在负载下降后自动继续。" type="warning" :closable="false" class="space-bottom" /><div class="kpi-grid">
        <el-card v-for="(value,label) in {总量:detail.counts.total,等待:detail.counts.waiting,运行:detail.counts.running,成功:detail.counts.success,失败:detail.counts.failed,取消:detail.counts.cancelled}" :key="label" shadow="never">
          <span class="metric-label">{{ label }}</span><strong class="metric-number">{{ value }}</strong>
        </el-card>
      </div><el-card shadow="never" class="space-top">
        <h2>整体进度</h2><el-progress :percentage="detail.progress" /><el-table :data="detail.children" class="space-top">
          <el-table-column label="#" width="60">
            <template #default="scope">
              {{ scope.row.itemIndex+1 }}
            </template>
          </el-table-column><el-table-column label="任务" min-width="230">
            <template #default="scope">
              <router-link v-if="scope.row.jobNo" :to="'/jobs/'+scope.row.jobNo">
                {{ scope.row.jobNo }}
              </router-link><span v-else>{{ scope.row.admissionStatus==='REJECTED'?'接入失败':'等待接入' }}</span>
            </template>
          </el-table-column><el-table-column prop="analysisType" label="学科" /><el-table-column prop="conditionCode" label="工况" /><el-table-column label="状态">
            <template #default="scope">
              <StatusTag v-if="scope.row.status" :status="scope.row.status" /><span v-else>{{ scope.row.admissionStatus }}</span>
            </template>
          </el-table-column><el-table-column prop="nodeId" label="节点" /><el-table-column prop="pid" label="PID" /><el-table-column label="失败原因">
            <template #default="scope">
              {{ scope.row.failureMessage ?? scope.row.admissionFailureMessage }}
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
    <el-card v-else shadow="never">
      <h2>持久化批次</h2><el-table :data="rows" empty-text="尚无批次">
        <el-table-column label="批次" min-width="270">
          <template #default="scope">
            <router-link :to="'/batches/'+scope.row.batchNo">
              {{ scope.row.name }}
            </router-link><small class="block muted mono">{{ scope.row.batchNo }}</small>
          </template>
        </el-table-column><el-table-column label="状态">
          <template #default="scope">
            <StatusTag :status="scope.row.status" />
          </template>
        </el-table-column><el-table-column label="进度" min-width="160">
          <template #default="scope">
            <el-progress :percentage="scope.row.progress" />
          </template>
        </el-table-column><el-table-column label="成功 / 失败 / 总量">
          <template #default="scope">
            {{ scope.row.counts.success }} / {{ scope.row.counts.failed }} / {{ scope.row.counts.total }}
          </template>
        </el-table-column><el-table-column prop="submitterId" label="提交人" /><el-table-column label="创建时间">
          <template #default="scope">
            {{ time(scope.row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="dialog" title="创建批次" width="min(650px,94vw)">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="name" maxlength="128" />
        </el-form-item><el-form-item label="项目">
          <el-select v-model="project" class="full">
            <el-option v-for="row in projects" :key="row.id" :label="row.name" :value="row.id" />
          </el-select>
        </el-form-item><el-form-item label="公开案例">
          <el-select v-model="selected" multiple class="full">
            <el-option v-for="row in eligible" :key="row.casePath" :label="row.caseId+' · '+row.conditionCode" :value="row.casePath" />
          </el-select>
        </el-form-item><div class="form-row">
          <el-form-item label="每个案例的任务数">
            <el-input-number v-model="copies" :min="1" :max="200" />
          </el-form-item><el-form-item label="资源档位">
            <el-select v-model="profile">
              <el-option v-for="row in profiles.filter(row=>row.enabled)" :key="row.name" :label="row.name" :value="row.name" />
            </el-select>
          </el-form-item>
        </div><p class="muted">
          共 {{ total }} 个任务，每批最多 200 个。案例中的工况与 Subcase 会保留到结果与报告。
        </p>
      </el-form><template #footer>
        <el-button @click="dialog=false">
          返回
        </el-button><el-button type="primary" :loading="busy" :disabled="total<1||total>200||!name.trim()" @click="create">
          创建并逐步提交
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
