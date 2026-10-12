<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorText } from '../api/client'
import type {Case, Project, Profile, Revision} from '../api/types'
const router = useRouter(); const projects = ref<Project[]>([]); const revisions = ref<Revision[]>([]); const cases = ref<Case[]>([]); const profiles = ref<Profile[]>([])
const revisionChoice=ref<number>(); const analysisChoice=ref(''); const project = ref<number>(); const casePath = ref(''); const profile = ref('SMALL'); const priority = ref(50); const busy = ref(false); const error = ref('')
const selected = computed(() => cases.value.find(row => row.casePath === casePath.value))
const eligibleCases = computed(() => cases.value.filter(row => revisions.value.some(revision => revision.revision === row.modelRevision && (!revisionChoice.value || revision.id===revisionChoice.value)) && (!analysisChoice.value || row.analysisType===analysisChoice.value)))
const eligibleProfiles = computed(() => profiles.value.filter(row => row.enabled && (!selected.value || row.allowedAnalysisTypes.includes(selected.value.analysisType))))
const resource = computed(() => profiles.value.find(row => row.name === profile.value))
let revisionSequence = 0; let key = crypto.randomUUID(); let previousBody = ''; let createdJob = ''; let submitKey = crypto.randomUUID()
watch(project, async () => { const sequence = ++revisionSequence; casePath.value = ''; revisionChoice.value=undefined; analysisChoice.value=''; revisions.value = []; if (project.value) { try { const response = await api.get<Revision[]>('/model-revisions', {params: {projectId: project.value}}); if (sequence === revisionSequence) revisions.value = response.data } catch (failure) { error.value = errorText(failure) } } })
onMounted(async () => { try { const results = await Promise.all([api.get<Project[]>('/projects'), api.get<Case[]>('/cases'), api.get<Profile[]>('/resource-profiles')]); projects.value = results[0].data; cases.value = results[1].data; profiles.value = results[2].data; project.value = projects.value[0]?.id } catch (failure) { error.value = errorText(failure) } })
watch(casePath,()=>{if(selected.value){revisionChoice.value=revisions.value.find(row=>row.revision===selected.value?.modelRevision)?.id;analysisChoice.value=selected.value.analysisType}})
watch([revisionChoice,analysisChoice],()=>{if(selected.value&&!eligibleCases.value.includes(selected.value))casePath.value=''})
async function create(andSubmit: boolean) {
  if (busy.value || !selected.value || !project.value) return
  const revision = revisions.value.find(row => row.revision === selected.value?.modelRevision)
  if (!revision) { error.value = '所选案例的模型版本不属于当前项目。'; return }
  const body = {projectId: project.value, modelRevisionId: revision.id, analysisType: selected.value.analysisType, conditionCode: selected.value.conditionCode, casePath: casePath.value, resourceProfile: profile.value, priority: priority.value}
  const encoded = JSON.stringify(body); if (previousBody && previousBody !== encoded) { key = crypto.randomUUID(); createdJob = ''; submitKey = crypto.randomUUID() }; previousBody = encoded
  busy.value = true; error.value = ''
  try { if (!createdJob) createdJob = (await api.post<{jobNo: string}>('/jobs', body, {headers: {'Idempotency-Key': key}})).data.jobNo; if (andSubmit) await api.post(`/jobs/${createdJob}/submit`, {idempotencyKey: submitKey}); await router.push(`/jobs/${createdJob}`) } catch (failure) { error.value = errorText(failure) } finally { busy.value = false }
}
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">
          NEW SIMULATION
        </p><h1>创建仿真任务</h1><p class="muted">
          选择项目、工况与资源档位，提交后持续追踪完整结果链路。
        </p>
      </div><router-link to="/jobs">
        返回任务列表
      </router-link>
    </div><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="space-bottom" /><div class="create-layout">
      <el-card shadow="never">
        <el-form label-position="top" @submit.prevent="create(true)">
          <el-form-item label="工程项目">
            <el-select v-model="project" class="full" placeholder="选择项目">
              <el-option v-for="row in projects" :key="row.id" :label="row.name+' · '+row.code" :value="row.id" />
            </el-select>
          </el-form-item><div class="form-row">
            <el-form-item label="模型版本">
              <el-select v-model="revisionChoice" clearable placeholder="全部版本">
                <el-option v-for="row in revisions" :key="row.id" :label="row.revision" :value="row.id" />
              </el-select>
            </el-form-item><el-form-item label="分析学科">
              <el-select v-model="analysisChoice" clearable placeholder="全部学科">
                <el-option v-for="name in ['STRUCTURE','NVH','CRASH','CFD']" :key="name" :label="name" :value="name" />
              </el-select>
            </el-form-item>
          </div><el-form-item label="公开案例 / 工况">
            <el-select v-model="casePath" class="full" placeholder="选择案例">
              <el-option v-for="row in eligibleCases" :key="row.casePath" :label="row.caseId+' · '+row.conditionCode+' / Subcase '+row.subcase" :value="row.casePath" />
            </el-select>
          </el-form-item><div v-if="selected" class="case-summary">
            <el-tag>{{ selected.analysisType }}</el-tag><el-tag type="info">
              {{ selected.modelRevision }}
            </el-tag><el-tag :type="selected.analysisType==='STRUCTURE'?'success':'warning'">
              {{ selected.analysisType==='STRUCTURE'?'结构 / 模态完整流程':'PILOT' }}
            </el-tag><p>{{ selected.conditionCode }} · Subcase {{ selected.subcase }}</p>
          </div><el-form-item label="资源档位">
            <el-select v-model="profile" class="full">
              <el-option v-for="row in eligibleProfiles" :key="row.name" :value="row.name" :label="row.name+' · '+row.cpu+' CPU / '+row.memoryGb+' GiB'" />
            </el-select>
          </el-form-item><el-form-item label="调度优先级">
            <el-slider v-model="priority" :max="100" show-input />
          </el-form-item><div class="actions">
            <el-button type="primary" native-type="submit" :loading="busy" :disabled="!selected">
              创建并提交
            </el-button><el-button :disabled="busy || !selected" @click="create(false)">
              仅创建
            </el-button>
          </div>
        </el-form>
      </el-card><aside>
        <el-card shadow="never">
          <p class="eyebrow">
            RESOURCE PLAN
          </p><h2>{{ profile }}</h2><div v-if="resource" class="resource-spec">
            <span>CPU<strong>{{ resource.cpu }} 核</strong></span><span>内存<strong>{{ resource.memoryGb }} GiB</strong></span><span>时限<strong>{{ resource.walltimeMin }} 分钟</strong></span><span>License<strong>{{ resource.licenseTokens }} Token</strong></span>
          </div><p class="muted">
            资源不足时任务进入等待队列，容量可用后继续调度。
          </p>
        </el-card><p class="help-text">
          案例来自公开 Synthetic 数据。工况、模型版本和 Subcase 将沿结果与报告流程保持一致。
        </p>
      </aside>
    </div>
  </section>
</template>
