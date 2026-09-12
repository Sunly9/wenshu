<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ChatDotRound, CopyDocument, RefreshLeft } from '@element-plus/icons-vue'
import { errMsg, kbApi } from '../api/http'
import type { DocStatus, KbInfo } from '../api/types'

const route = useRoute()
const router = useRouter()
const kbId = Number(route.params.id)

const kb = ref<KbInfo | null>(null)
const docs = ref<DocStatus[]>([])
const uploading = ref(false)
let timer: number | undefined

const hasProcessing = computed(() =>
  docs.value.some((d) => d.status === 'PENDING' || d.status === 'PARSING' || d.status === 'INDEXING'),
)
const readyCount = computed(() => docs.value.filter((d) => d.status === 'READY').length)

async function load() {
  try {
    const [kbList, docList] = await Promise.all([
      kbApi.list(),
      kbApi.documents(kbId),
    ])
    kb.value = kbList.find((k) => k.id === kbId) ?? null
    docs.value = docList
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

async function upload(file: File) {
  uploading.value = true
  try {
    await kbApi.upload(kbId, file)
    ElMessage.success(`已接收「${file.name}」，开始解析`)
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    uploading.value = false
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    void upload(input.files[0])
    input.value = ''
  }
}

async function resetCode() {
  if (!kb.value) return
  try {
    const updated = await kbApi.resetCode(kbId)
    kb.value = { ...kb.value, shareCode: updated.shareCode }
    ElMessage.success(`口令已重置为 ${updated.shareCode}，旧口令即刻失效`)
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

function copy(text: string) {
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
}

function joinLink() {
  return `${location.origin}/join/${kb.value?.shareCode ?? ''}`
}

function statusType(s: DocStatus['status']) {
  return { READY: 'success', FAILED: 'danger', PENDING: 'info', PARSING: 'warning', INDEXING: 'warning' }[s]
}

function statusText(s: DocStatus['status']) {
  return { PENDING: '排队中', PARSING: '解析中', INDEXING: '建立索引', READY: '就绪', FAILED: '失败' }[s]
}

onMounted(() => {
  void load()
  timer = window.setInterval(() => {
    if (hasProcessing.value) void load()
  }, 1500)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div v-if="kb">
    <div class="page-head">
      <div class="head-left">
        <el-button :icon="ArrowLeft" text @click="router.push('/')" />
        <h2>{{ kb.name }}</h2>
        <el-tag v-if="readyCount > 0" type="success" size="small">{{ readyCount }} 份就绪</el-tag>
      </div>
      <el-button type="primary" :icon="ChatDotRound" @click="router.push(`/chat/${kbId}`)">去提问</el-button>
    </div>

    <el-card class="upload-card">
      <label class="upload-zone">
        <input type="file" accept=".pdf,.docx,.md,.markdown" hidden @change="onFileChange" />
        <span v-if="!uploading">📄 点击或拖入文件上传（PDF / Word / Markdown，≤ 50MB）</span>
        <span v-else>上传中…</span>
      </label>
      <div class="share-bar">
        <template v-if="kb.owner">
          <span>库口令：<b class="code">{{ kb.shareCode }}</b></span>
          <el-button text size="small" :icon="CopyDocument" @click="copy(kb.shareCode)">复制口令</el-button>
          <el-button text size="small" :icon="CopyDocument" @click="copy(joinLink())">复制加入链接</el-button>
          <el-button text size="small" :icon="RefreshLeft" @click="resetCode">重置</el-button>
        </template>
        <span v-else class="share-tip">此库由同学创建，你已通过口令加入</span>
      </div>
    </el-card>

    <el-table :data="docs" style="width: 100%">
      <el-table-column prop="fileName" label="文件" min-width="260" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="200">
        <template #default="{ row }">
          <el-progress
            v-if="row.status !== 'READY' && row.status !== 'FAILED'"
            :percentage="row.percent"
            :stroke-width="8"
          />
          <span v-else-if="row.status === 'FAILED'" class="err">{{ row.errorMsg }}</span>
          <span v-else>✓ 可被检索</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 6px;
}
.head-left h2 {
  margin: 0;
}
.upload-card {
  margin-bottom: 16px;
}
.upload-zone {
  display: block;
  border: 1.5px dashed var(--ws-border);
  border-radius: 8px;
  padding: 28px;
  text-align: center;
  cursor: pointer;
  color: var(--ws-text-light);
}
.upload-zone:hover {
  border-color: var(--ws-primary);
  color: var(--ws-primary);
}
.share-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
  font-size: 13px;
  color: var(--ws-text-light);
}
.code {
  color: var(--ws-primary);
  letter-spacing: 3px;
  font-size: 15px;
}
.share-tip {
  font-size: 12px;
}
.err {
  color: #f56c6c;
  font-size: 12px;
}
</style>
