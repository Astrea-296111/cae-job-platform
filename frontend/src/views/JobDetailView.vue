<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, download, errorText, time } from '../api/client'
import { stream } from '../api/stream'
import { useSessionStore } from '../stores/session'
import type {Artifact, Execution, Job, JobEvent, Metric, Parse, PipelineStage, Report} from '../api/types'
import StatusTag from '../components/StatusTag.vue'
const priorityDialog=ref(false);const priority=ref(50);
function openPriority(){priority.value=job.value?.priority??50;priorityDialog.value=true}
async function savePriority(){try{await api.patch('/jobs/'+number+'/priority',{priority:priority.value});priorityDialog.value=false;await load();ElMessage.success('优先级已更新。')}catch(failure){ElMessage.error(errorText(failure))}}
const number = String(useRoute().params.jobNo); const session = useSessionStore(); const job = ref<Job>(); const events = ref<JobEvent[]>([]); const executions = ref<Execution[]>([]); const parses = ref<Parse[]>([]); const metrics = ref<Metric[]>([]); const artifacts = ref<Artifact[]>([]); const reports = ref<Report[]>([]); const stages = ref<PipelineStage[]>([])
const version = ref('v1'); const tab = ref('overview'); const error = ref(''); const busy = ref(false); const actionBusy = ref(false); const streamState = ref('连接中'); const synced = ref<string>(); const log = ref(''); const logExecution = ref<number>(); const images = ref<{artifact: Artifact; url: string}[]>([])
const owner = computed(() => session.admin() || job.value?.submitterId === session.username)
const canSubmit = computed(() => owner.value && ['CREATED','READY','VALIDATION_FAILED'].includes(job.value?.status ?? ''))
const canRetry = computed(() => owner.value && ['VALIDATION_FAILED','SUBMIT_FAILED','RUNTIME_FAILED','POST_PROCESS_FAILED','REPORT_FAILED','CANCELLED'].includes(job.value?.status ?? ''))
const canCancel = computed(() => owner.value && ['CREATED','VALIDATING','READY','QUEUED','WAITING_RESOURCE','SUBMITTING','SUBMITTED','RUNNING','UNKNOWN','CANCEL_REQUESTED'].includes(job.value?.status ?? ''))
const current = computed(() => executions.value.find(row => row.id === job.value?.currentExecutionId))
let disposed=false; let pendingRefresh = false; let inflight = false; let stop: (()=>void) | undefined; let timer: ReturnType<typeof setInterval>; let imageSignature = ''; let imageGeneration = 0
const keys = new Map<string, string>()
function key(operation: string) { if (!keys.has(operation)) keys.set(operation, crypto.randomUUID()); return keys.get(operation) }
async function previews() {
  const selected = artifacts.value.filter(row => row.artifactType === 'IMAGE' && row.parserVersion === version.value)
  const signature = selected.map(row=>`${row.id}:${row.checksum}`).join('|'); if (signature === imageSignature) return
  const generation = ++imageGeneration; const loaded: {artifact:Artifact; url:string}[] = []
  try { for (const artifact of selected) { const response = await api.get(artifact.downloadUrl.slice(7), {responseType: 'blob'}); loaded.push({artifact,url:URL.createObjectURL(response.data)}) }; if (generation === imageGeneration) { images.value.forEach(row=>URL.revokeObjectURL(row.url)); images.value = loaded; imageSignature = signature } else loaded.forEach(row=>URL.revokeObjectURL(row.url)) } catch (failure) { loaded.forEach(row=>URL.revokeObjectURL(row.url)); error.value = errorText(failure) }
}
async function logs() { try { const response = await api.get<{lines:string[]}>('/jobs/'+number+'/logs', {params:{executionId:logExecution.value,lines:200}}); log.value = response.data.lines.join('\n') } catch (failure) { log.value = errorText(failure) } }
async function load() {
  if (inflight) { pendingRefresh = true; return }; inflight = true; const requestedVersion = version.value; if (!job.value) busy.value = true
  try { const responses = await Promise.all([api.get<Job>('/jobs/'+number),api.get<JobEvent[]>('/jobs/'+number+'/events'),api.get<Execution[]>('/jobs/'+number+'/executions'),api.get<Parse[]>('/jobs/'+number+'/parses'),api.get<Artifact[]>('/jobs/'+number+'/artifacts'),api.get<Report[]>('/jobs/'+number+'/reports'),api.get<PipelineStage[]>('/jobs/'+number+'/pipeline'),api.get<Metric[]>('/jobs/'+number+'/metrics',{params:{parserVersion:requestedVersion}})]); if(disposed)return;job.value=responses[0].data;events.value=responses[1].data;executions.value=responses[2].data;parses.value=responses[3].data;artifacts.value=responses[4].data;reports.value=responses[5].data;stages.value=responses[6].data;if (requestedVersion === version.value) metrics.value=responses[7].data; else pendingRefresh=true; synced.value=new Date().toISOString(); error.value=''; await previews(); await logs() } catch (failure) { error.value=errorText(failure) } finally {busy.value=false;inflight=false;if(pendingRefresh&&!disposed){pendingRefresh=false;void load()}}
}
async function command(operation: string) { actionBusy.value=true;try { await api.post(`/jobs/${number}/${operation}`,{idempotencyKey:key(operation)});keys.delete(operation);ElMessage.success('请求已接受，状态以服务端确认结果为准。');await load() } catch (failure) { ElMessage.error(errorText(failure)) } finally { actionBusy.value=false } }
async function reparse() { actionBusy.value=true;try {await api.post(`/jobs/${number}/reparse`,{parserVersion:'v2'});version.value='v2';await load();ElMessage.success('已请求 v2 解析，原版本保留。')}catch(failure){ElMessage.error(errorText(failure))}finally{actionBusy.value=false} }
async function draft() { actionBusy.value=true;try{await api.post(`/jobs/${number}/reports`,{parserVersion:version.value,idempotencyKey:key('draft-'+version.value)});keys.delete('draft-'+version.value);await load();ElMessage.success('已请求新草稿版本。')}catch(failure){ElMessage.error(errorText(failure))}finally{actionBusy.value=false} }
async function getFile(artifact: Artifact) { try { await download(artifact.downloadUrl,`${number}_${artifact.parserVersion}_${artifact.stageVersion}_${artifact.fileName}`) } catch(failure) {ElMessage.error(errorText(failure))} }
onMounted(async()=>{await load();if(disposed)return;stop=stream({jobNo:number},()=>void load(),value=>streamState.value=value);timer=setInterval(()=>{if(!document.hidden) void load()},10000)})
onUnmounted(()=>{disposed=true;stop?.();clearInterval(timer);imageGeneration++;images.value.forEach(row=>URL.revokeObjectURL(row.url))})
</script>
<template>
  <section v-loading="busy">
    <div class="page-heading">
      <div>
        <p class="eyebrow">
          JOB WORKSPACE
        </p><h1 class="mono job-title">
          {{ number }}
        </h1><p class="muted">
          {{ job?.analysisType }} · {{ job?.conditionCode }} · {{ job?.resourceProfileId }}<span class="stream-label">● {{ streamState }}</span>
        </p>
      </div><div class="actions">
        <el-button v-if="session.lead()" @click="openPriority">
          调整优先级
        </el-button><el-button v-if="canSubmit" type="primary" :loading="actionBusy" @click="command('submit')">
          提交执行
        </el-button><el-button v-if="canRetry" type="primary" :loading="actionBusy" @click="command('retry')">
          重试当前阶段
        </el-button><el-button v-if="canCancel" type="danger" plain :loading="actionBusy" @click="command('cancel')">
          请求取消
        </el-button><el-button @click="load">
          刷新
        </el-button>
      </div>
    </div><el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="space-bottom" /><el-alert v-if="job?.failureMessage" :title="job.failureCode+' · '+job.failureMessage" :type="job.status==='WAITING_RESOURCE'?'warning':'error'" show-icon :closable="false" class="space-bottom" /><div v-if="job" class="job-summary">
      <StatusTag :status="job.status" /><span>阶段 <strong>{{ job.stage }}</strong></span><span>提交人 <strong>{{ job.submitterId }}</strong></span><span>节点 <strong>{{ current?.nodeId ?? '—' }}</strong></span><span>创建 <strong>{{ time(job.createdAt) }}</strong></span><span>资源 <strong>{{ job.resource.cpu }} CPU / {{ job.resource.memoryGb }} GiB / {{ job.resource.walltimeMin }} min</strong></span>
    </div><el-tabs v-model="tab" class="detail-tabs">
      <el-tab-pane label="执行与时间线" name="overview">
        <div class="detail-grid">
          <el-card shadow="never">
            <h2>执行历史</h2><el-table :data="executions" empty-text="尚未开始执行">
              <el-table-column prop="id" label="执行 ID" width="90" /><el-table-column prop="nodeId" label="节点" /><el-table-column prop="status" label="执行状态" /><el-table-column prop="pid" label="PID" /><el-table-column label="进度">
                <template #default="scope">
                  <el-progress :percentage="Number(scope.row.progress ?? 0)" />
                </template>
              </el-table-column>
            </el-table><div v-if="current" class="execution-facts">
              <span>进程身份 <code>{{ current.processIdentity }}</code></span><span>开始 {{ time(current.startedAt) }}</span><span>结束 {{ time(current.finishedAt) }}</span><span v-if="current.rawSolverCode" class="red">Solver 原始错误：{{ current.rawSolverCode }} · {{ current.rawMessage }}</span>
            </div><h3>结果与报告阶段</h3><el-table :data="stages" empty-text="等待求解完成">
              <el-table-column prop="kind" label="阶段" /><el-table-column prop="parserVersion" label="解析版本" /><el-table-column prop="stageVersion" label="版本" /><el-table-column prop="status" label="状态" /><el-table-column prop="failureMessage" label="错误" />
            </el-table>
          </el-card><el-card shadow="never">
            <h2>持久化事件</h2><el-timeline class="timeline">
              <el-timeline-item v-for="event in [...events].reverse().slice(0,40)" :key="event.id" :timestamp="time(event.createdAt)" placement="top">
                <strong>{{ event.eventType }}</strong><p v-if="event.toStatus" class="muted">
                  {{ event.fromStatus }} → {{ event.toStatus }}
                </p>
              </el-timeline-item>
            </el-timeline>
          </el-card>
        </div>
      </el-tab-pane><el-tab-pane label="Solver 日志" name="logs">
        <el-card shadow="never">
          <div class="panel-heading">
            <h2>执行日志</h2><div class="actions">
              <el-select v-model="logExecution" placeholder="当前执行" clearable @change="logs">
                <el-option v-for="row in executions" :key="row.id" :value="row.id" :label="'执行 '+row.id+' · '+row.nodeId" />
              </el-select><el-button @click="logs">
                刷新日志
              </el-button>
            </div>
          </div><pre class="log-panel">{{ log || '等待 Solver 日志。' }}</pre><p class="muted small">
            最近 200 行 · 运行期间持续更新 · 平台错误与 Solver 原始错误分别显示。
          </p>
        </el-card>
      </el-tab-pane><el-tab-pane label="指标与图片" name="metrics">
        <div class="panel-heading">
          <div class="actions">
            <el-select v-model="version" @change="load">
              <el-option label="Parser v1" value="v1" /><el-option label="Parser v2" value="v2" />
            </el-select><el-tag v-for="parse in parses.filter(row=>row.parserVersion===version)" :key="parse.id" :type="parse.maturity==='COMPLETE'?'success':'warning'">
              {{ parse.maturity }} · {{ parse.status }}
            </el-tag>
          </div><el-button v-if="owner && current?.status==='SUCCESS'" :loading="actionBusy" @click="reparse">
            使用 v2 重解析
          </el-button>
        </div><el-card shadow="never">
          <el-table :data="metrics" empty-text="等待解析指标">
            <el-table-column prop="metricName" label="指标" min-width="170" /><el-table-column prop="metricCode" label="Canonical Code" min-width="220" /><el-table-column label="数值 / 文本">
              <template #default="scope">
                {{ scope.row.valueNum ?? scope.row.valueText }}
              </template>
            </el-table-column><el-table-column prop="unit" label="单位" /><el-table-column prop="status" label="质量" /><el-table-column prop="conditionCode" label="工况" /><el-table-column prop="subcase" label="Subcase" />
          </el-table>
        </el-card><div class="image-grid">
          <el-card v-for="item in images" :key="item.artifact.id" shadow="never">
            <div class="panel-heading">
              <h3>{{ item.artifact.metadata.resultType }}</h3><el-button text @click="getFile(item.artifact)">
                下载图片
              </el-button>
            </div><img :src="item.url" :alt="item.artifact.metadata.resultType+' '+item.artifact.metadata.conditionCode+' Subcase '+item.artifact.metadata.subcase" /><p class="muted small">
              {{ item.artifact.metadata.conditionCode }} / Subcase {{ item.artifact.metadata.subcase }} · {{ item.artifact.parserVersion }} / Post {{ item.artifact.stageVersion }}
            </p>
          </el-card>
        </div>
      </el-tab-pane><el-tab-pane label="报告与工件" name="reports">
        <el-card shadow="never">
          <div class="panel-heading">
            <h2>报告版本</h2><el-button v-if="['SUCCESS','POST_PROCESS_FAILED','REPORT_FAILED'].includes(job?.status ?? '')" :loading="actionBusy" @click="draft">
              生成新草稿
            </el-button>
          </div><el-table :data="reports" empty-text="等待生成草稿报告">
            <el-table-column prop="parserVersion" label="解析" /><el-table-column prop="reportVersion" label="报告版本" /><el-table-column label="审核状态">
              <template #default="scope">
                <StatusTag :status="scope.row.reportStatus" />
              </template>
            </el-table-column><el-table-column prop="baselineJobNo" label="基线" min-width="220" /><el-table-column label="操作">
              <template #default="scope">
                <el-button text type="primary" @click="download(scope.row.downloadUrl,number+'_'+scope.row.parserVersion+'_report-v'+scope.row.reportVersion+'.pptx')">
                  下载 PPTX
                </el-button>
              </template>
            </el-table-column>
          </el-table><p class="muted">
            报告生成完成后仍为 DRAFT。人工审核和批准请前往<router-link to="/reports">
              报告中心
            </router-link>。
          </p>
        </el-card><el-card shadow="never" class="space-top">
          <h2>登记工件</h2><el-table :data="artifacts" empty-text="尚无工件">
            <el-table-column prop="artifactType" label="类型" width="160" /><el-table-column prop="fileName" label="文件" /><el-table-column prop="parserVersion" label="解析版本" /><el-table-column prop="stageVersion" label="阶段版本" /><el-table-column prop="sizeBytes" label="字节" /><el-table-column prop="checksum" label="SHA-256" min-width="220" show-overflow-tooltip /><el-table-column label="操作">
              <template #default="scope">
                <el-button text @click="getFile(scope.row)">
                  下载
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs><p class="muted small">
      最后同步 {{ time(synced) }} · 事件通知后重新读取服务端状态。
    </p><el-dialog v-model="priorityDialog" title="调整优先级" width="min(450px,94vw)">
      <el-input-number v-model="priority" :min="0" :max="100" /><template #footer>
        <el-button @click="priorityDialog=false">
          返回
        </el-button><el-button type="primary" @click="savePriority">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
