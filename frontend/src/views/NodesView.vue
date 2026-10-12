<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorText, time } from '../api/client'
import type {Node, Profile} from '../api/types'
import { useSessionStore } from '../stores/session'
const edit=ref<Profile>();const editDialog=ref(false);function openEdit(profile:Profile){edit.value={...profile,allowedAnalysisTypes:[...profile.allowedAnalysisTypes]};editDialog.value=true}
async function saveProfile(){if(!edit.value)return;try{await api.put('/resource-profiles/'+edit.value.name,edit.value);editDialog.value=false;await load();ElMessage.success('资源档位已更新，新任务使用新版本。')}catch(failure){ElMessage.error(errorText(failure))}}
const session=useSessionStore();const nodes=ref<Node[]>([]);const profiles=ref<Profile[]>([]);const licenses=ref<{feature:string;totalTokens:number;reservedTokens:number;inUseTokens:number}[]>([]);const error=ref('');const busy=ref(false)
async function load(){busy.value=true;try{const responses=await Promise.all([api.get<Node[]>('/compute-nodes'),api.get<Profile[]>('/resource-profiles'),api.get('/license-pools')]);nodes.value=responses[0].data;profiles.value=responses[1].data;licenses.value=responses[2].data;error.value=''}catch(failure){error.value=errorText(failure)}finally{busy.value=false}}
async function drain(node:Node){try{await api.patch(`/compute-nodes/${node.nodeId}/drain`,{drained:!node.drained});await load();ElMessage.success(node.drained?'已恢复调度。':'已停止向该节点分配新任务。')}catch(failure){ElMessage.error(errorText(failure))}}
let timer:ReturnType<typeof setInterval>;onMounted(()=>{void load();timer=setInterval(()=>{if(!document.hidden)void load()},10000)});onUnmounted(()=>clearInterval(timer))
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">
          COMPUTE RESOURCES
        </p><h1>计算资源</h1><p class="muted">
          调度账本、节点观测与 License 分开追踪。
        </p>
      </div><el-button :loading="busy" @click="load">
        刷新资源
      </el-button>
    </div><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="space-bottom" /><div class="nodes-grid">
      <el-card v-for="node in nodes" :key="node.nodeId" shadow="never">
        <div class="panel-heading">
          <h2 class="mono">
            {{ node.nodeId }}
          </h2><el-tag :type="node.status==='ONLINE'?'success':'warning'">
            {{ node.status }}
          </el-tag>
        </div><div class="node-tags">
          <el-tag v-for="tag in node.solverTags" :key="tag" size="small" type="info">
            {{ tag }}
          </el-tag><el-tag v-if="node.drained" size="small" type="warning">
            DRAINED
          </el-tag>
        </div><h3>CPU 账本</h3><el-progress :percentage="Math.min(100,Math.round((node.cpuUsed+node.cpuReserved)/node.cpuTotal*100))" color="#237f79" /><p class="muted">
          {{ node.cpuUsed }} 使用 / {{ node.cpuReserved }} 预留 / {{ node.cpuTotal }} 总量
        </p><h3>内存账本</h3><el-progress :percentage="Math.min(100,Math.round((node.memoryUsedGb+node.memoryReservedGb)/node.memoryTotalGb*100))" color="#6683b6" /><p class="muted">
          {{ node.memoryUsedGb }} 使用 / {{ node.memoryReservedGb }} 预留 / {{ node.memoryTotalGb }} GiB 总量
        </p><dl class="node-facts">
          <dt>Agent 观测</dt><dd>{{ node.observedCpuUsed }} CPU · {{ node.observedMemoryUsedGb }} GiB</dd><dt>共享目录</dt><dd>{{ node.workspaceReachable?'可访问':'不可访问' }}</dd><dt>最近心跳</dt><dd>{{ time(node.lastHeartbeat) }}</dd>
        </dl><el-button v-if="session.admin()" :type="node.drained?'primary':'warning'" plain @click="drain(node)">
          {{ node.drained?'恢复调度':'暂停新分配' }}
        </el-button>
      </el-card>
    </div><el-card shadow="never" class="space-top">
      <h2>License Token</h2><el-table :data="licenses">
        <el-table-column prop="feature" label="Feature" /><el-table-column prop="totalTokens" label="总量" /><el-table-column prop="reservedTokens" label="预留" /><el-table-column prop="inUseTokens" label="使用" />
      </el-table><p class="muted small">
        公开版使用模拟 Token 账本，不需要商业许可证。
      </p>
    </el-card><el-card shadow="never" class="space-top">
      <h2>资源档位</h2><el-table :data="profiles">
        <el-table-column prop="name" label="名称" /><el-table-column prop="cpu" label="CPU" /><el-table-column prop="memoryGb" label="内存 GiB" /><el-table-column prop="walltimeMin" label="时限 min" /><el-table-column prop="licenseTokens" label="Token" /><el-table-column label="支持学科">
          <template #default="scope">
            {{ scope.row.allowedAnalysisTypes.join(' · ') }}
          </template>
        </el-table-column><el-table-column label="状态">
          <template #default="scope">
            {{ scope.row.enabled?'可用':'停用' }}
          </template>
        </el-table-column><el-table-column prop="version" label="版本" /><el-table-column v-if="session.admin()" label="操作">
          <template #default="scope">
            <el-button text @click="openEdit(scope.row)">
              编辑
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="editDialog" title="编辑资源档位" width="min(600px,94vw)">
      <el-form v-if="edit" label-position="top">
        <h3>{{ edit.name }}</h3><div class="form-row">
          <el-form-item label="CPU">
            <el-input-number v-model="edit.cpu" :min="1" :max="32" />
          </el-form-item><el-form-item label="内存 GiB">
            <el-input-number v-model="edit.memoryGb" :min="1" :max="64" />
          </el-form-item>
        </div><div class="form-row">
          <el-form-item label="时限 min">
            <el-input-number v-model="edit.walltimeMin" :min="1" :max="120" />
          </el-form-item><el-form-item label="License Token">
            <el-input-number v-model="edit.licenseTokens" :min="1" :max="16" />
          </el-form-item>
        </div><el-form-item label="允许学科">
          <el-select v-model="edit.allowedAnalysisTypes" multiple>
            <el-option v-for="name in ['STRUCTURE','NVH','CRASH','CFD']" :key="name" :value="name" :label="name" />
          </el-select>
        </el-form-item><el-form-item label="启用">
          <el-switch v-model="edit.enabled" />
        </el-form-item>
      </el-form><template #footer>
        <el-button @click="editDialog=false">
          返回
        </el-button><el-button type="primary" @click="saveProfile">
          保存新版本
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
