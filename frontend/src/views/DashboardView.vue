<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([BarChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
import { api, errorText, time } from '../api/client'
import type {Dashboard, Project} from '../api/types'
import StatusTag from '../components/StatusTag.vue'
const data = ref<Dashboard>(); const projects = ref<Project[]>([]); const project = ref<number>(); const busy = ref(false); const error = ref(''); const chart = ref<HTMLElement>(); let instance: echarts.ECharts | undefined; let observer: ResizeObserver | undefined
const waiting = computed(() => data.value ? data.value.counts.queued + data.value.counts.waitingResource : 0)
async function load() { busy.value = true; error.value = ''; try { data.value = (await api.get<Dashboard>('/dashboard', {params: {projectId: project.value, timeZone: 'Asia/Shanghai'}})).data; await nextTick(); if (chart.value) { instance ??= echarts.init(chart.value); const disciplines = [...new Set(data.value.byAnalysisType.map(row => row.analysisType))]; const statuses = [...new Set(data.value.byAnalysisType.map(row=>row.status))]; instance.setOption({color:['#157c73','#3875d7','#dda943','#7d83b5','#9badc7','#d27171'],tooltip:{trigger:'axis',axisPointer:{type:'shadow'}},legend:{bottom:0,type:'scroll'},grid:{left:40,right:25,top:20,bottom:60},xAxis:{type:'category',data:disciplines},yAxis:{type:'value',minInterval:1},series:statuses.map(status=>({name:status,type:'bar',stack:'jobs',barMaxWidth:50,data:disciplines.map(type=>data.value?.byAnalysisType.filter(row=>row.analysisType===type && row.status===status).reduce((sum,row)=>sum+row.count,0) ?? 0)}))}); observer ??= new ResizeObserver(()=>instance?.resize()); observer.observe(chart.value) } } catch (failure) { error.value = errorText(failure) } finally { busy.value = false } }
let timer: ReturnType<typeof setInterval>
onMounted(async () => { try { projects.value = (await api.get<Project[]>('/projects')).data; await load() } catch (failure) { error.value = errorText(failure) }; timer = setInterval(()=>{if(!document.hidden) void load()},15000) })
onUnmounted(()=>{clearInterval(timer);observer?.disconnect();instance?.dispose()})
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">
          WORKSPACE OVERVIEW
        </p><h1>计算与结果，一目了然。</h1><p class="muted">
          当前工程任务、资源负载和可审阅结果。
        </p>
      </div><div class="actions">
        <el-select v-model="project" placeholder="全部项目" clearable @change="load">
          <el-option v-for="row in projects" :key="row.id" :label="row.name" :value="row.id" />
        </el-select><router-link to="/jobs/new">
          <el-button type="primary">
            ＋ 创建任务
          </el-button>
        </router-link>
      </div>
    </div><el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="space-bottom" /><div v-loading="busy && !data" class="kpi-grid">
      <el-card shadow="never">
        <span class="metric-label">求解中</span><strong class="metric-number">{{ data?.counts.running ?? '—' }}</strong><small>RUNNING · 已确认执行</small>
      </el-card><el-card shadow="never">
        <span class="metric-label">等待资源 / 排队</span><strong class="metric-number amber">{{ data ? waiting : '—' }}</strong><router-link to="/jobs?status=WAITING_RESOURCE">
          查看等待任务 →
        </router-link>
      </el-card><el-card shadow="never">
        <span class="metric-label">失败任务</span><strong class="metric-number red">{{ data?.counts.failed ?? '—' }}</strong><small>校验、提交、求解、处理及报告失败</small>
      </el-card><el-card shadow="never">
        <span class="metric-label">完成率</span><strong class="metric-number">{{ data?.successRate.percent == null ? '—' : data.successRate.percent+'%' }}</strong><small>{{ data?.successRate.numerator ?? 0 }} / {{ data?.successRate.denominator ?? 0 }} · 成功与失败终态</small>
      </el-card><el-card shadow="never">
        <span class="metric-label">平均排队时间</span><strong class="metric-number">{{ data?.averageQueueSeconds.value == null ? '—' : Number(data.averageQueueSeconds.value).toFixed(2)+'s' }}</strong><small>{{ data?.averageQueueSeconds.sampleCount ?? 0 }} 次执行 · 入队至开始</small>
      </el-card><el-card shadow="never">
        <span class="metric-label">计算节点</span><strong class="metric-number">{{ data?.nodes.filter(row=>row.status==='ONLINE').length ?? '—' }}<em>/ {{ data?.nodes.length ?? 0 }}</em></strong><router-link to="/compute-nodes">
          查看资源负载 →
        </router-link>
      </el-card>
    </div><div class="dashboard-grid">
      <el-card shadow="never">
        <div class="panel-heading">
          <h2>学科任务分布</h2><span class="muted">已授权项目 · 实际任务</span>
        </div><div ref="chart" class="chart"></div>
      </el-card><el-card shadow="never">
        <div class="panel-heading">
          <h2>实时计算资源</h2><router-link to="/compute-nodes">
            查看全部 →
          </router-link>
        </div><div v-for="node in data?.nodes" :key="node.nodeId" class="node-compact">
          <div>
            <strong class="mono">{{ node.nodeId }}</strong><el-tag :type="node.status==='ONLINE'?'success':'warning'" size="small">
              {{ node.status }}
            </el-tag>
          </div><el-progress :percentage="Math.round((node.cpuUsed+node.cpuReserved)/node.cpuTotal*100)" :show-text="false" color="#237f79" /><small class="muted">CPU {{ node.cpuUsed }} 使用 / {{ node.cpuReserved }} 预留 / {{ node.cpuTotal }} 总量</small>
        </div>
      </el-card>
    </div><el-card shadow="never" class="space-top">
      <div class="panel-heading">
        <h2>最近任务</h2><router-link to="/jobs">
          全部任务 →
        </router-link>
      </div><el-table :data="data?.recentJobs ?? []" empty-text="尚无任务">
        <el-table-column label="任务编号" min-width="230">
          <template #default="scope">
            <router-link :to="'/jobs/'+scope.row.jobNo" class="mono link">
              {{ scope.row.jobNo }}
            </router-link>
          </template>
        </el-table-column><el-table-column prop="analysisType" label="学科" /><el-table-column prop="conditionCode" label="工况" /><el-table-column label="状态">
          <template #default="scope">
            <StatusTag :status="scope.row.status" />
          </template>
        </el-table-column><el-table-column label="创建时间" min-width="180">
          <template #default="scope">
            {{ time(scope.row.createdAt) }}
          </template>
        </el-table-column>
      </el-table><p class="muted small">
        最后同步 {{ time(data?.asOf) }} · 资源负载为当前账本，完成率不包含取消与活跃任务。
      </p>
    </el-card>
  </section>
</template>
