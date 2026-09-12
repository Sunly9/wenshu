<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ChatDotRound, CopyDocument, RefreshLeft } from '@element-plus/icons-vue'
import { errMsg, kbApi } from '../api/http'
import type { ChunkPreviewResponse, PreviewBlock } from '../api/http'
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

// ---------- 分片预览 ----------
const previewStrategy = ref('STRUCTURE_AWARE')
const previewResult = ref<ChunkPreviewResponse | null>(null)
const previewFile = ref<File | null>(null)
const previewing = ref(false)
const selectedBlock = ref<PreviewBlock | null>(null)

function onPreviewFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  previewFile.value = input.files && input.files.length > 0 ? input.files[0] : null
}

async function runPreview() {
  if (!previewFile.value) {
    ElMessage.warning('请先选择要预览的文件')
    return
  }
  previewing.value = true
  selectedBlock.value = null
  try {
    previewResult.value = await kbApi.previewChunks(kbId, previewStrategy.value, previewFile.value)
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    previewing.value = false
  }
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

    <el-card class="upload-card">
      <template #header>
        <span class="card-title">分片预览</span>
        <span class="card-sub">上传前先看看文档会被切成什么样——切得不合理换策略重切（不入库）</span>
      </template>
      <div class="preview-controls">
        <el-select v-model="previewStrategy" style="width: 220px">
          <el-option label="结构感知（推荐·标题/表格/父子分块）" value="STRUCTURE_AWARE" />
          <el-option label="递归分隔符（按段落/句子切）" value="RECURSIVE" />
          <el-option label="固定长度（基线·硬切）" value="FIXED" />
        </el-select>
        <label class="preview-file">
          <input type="file" accept=".pdf,.docx,.md,.markdown" hidden @change="onPreviewFileChange" />
          {{ previewFile ? previewFile.name : '选择文件' }}
        </label>
        <el-button type="primary" :loading="previewing" @click="runPreview">预览切分</el-button>
      </div>
      <div v-if="previewResult" class="preview-body">
        <div class="preview-summary">
          策略 {{ previewResult.strategy }} · {{ previewResult.pageCount }} 页 ·
          父块 {{ previewResult.parentCount }} / 子块 {{ previewResult.childCount }} ·
          共 {{ previewResult.totalChildTokens }} token
          <span v-if="previewResult.truncated" class="warn">（仅展示前 {{ previewResult.blocks.length }} 块）</span>
        </div>
        <div class="preview-panes">
          <div class="block-list">
            <div
              v-for="b in previewResult.blocks"
              :key="b.kind + b.index"
              class="block-item"
              :class="{ active: selectedBlock === b }"
              @click="selectedBlock = b"
            >
              <el-tag size="small" :type="b.kind === 'parent' ? 'warning' : 'success'">
                {{ b.kind === 'parent' ? '父' : '子' }}{{ b.index }}
              </el-tag>
              <span class="block-tok">{{ b.tokenCount }}t</span>
              <span class="block-section">{{ b.table ? '[表格] ' : '' }}{{ b.sectionPath || '—' }}</span>
            </div>
          </div>
          <div class="block-detail">
            <template v-if="selectedBlock">
              <div class="detail-meta">
                {{ selectedBlock.kind === 'parent' ? '父块' : '子块' }}{{ selectedBlock.index }}
                <template v-if="selectedBlock.parentIndex !== null"> · 挂父块{{ selectedBlock.parentIndex }}</template>
                · {{ selectedBlock.tokenCount }} token
                <template v-if="selectedBlock.pageNo"> · 第{{ selectedBlock.pageNo }}页</template>
                <template v-if="selectedBlock.sectionPath"> · {{ selectedBlock.sectionPath }}</template>
              </div>
              <pre class="detail-content">{{ selectedBlock.content }}</pre>
            </template>
            <el-empty v-else description="点击左侧块查看内容" :image-size="60" />
          </div>
        </div>
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
.card-title {
  font-weight: 600;
}
.card-sub {
  margin-left: 10px;
  font-size: 12px;
  color: var(--ws-text-light);
}
.preview-controls {
  display: flex;
  gap: 10px;
  align-items: center;
}
.preview-file {
  border: 1px solid var(--ws-border);
  border-radius: 4px;
  padding: 6px 14px;
  cursor: pointer;
  font-size: 13px;
  color: var(--ws-text-light);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-file:hover {
  border-color: var(--ws-primary);
  color: var(--ws-primary);
}
.preview-body {
  margin-top: 14px;
}
.preview-summary {
  font-size: 13px;
  color: var(--ws-text-light);
  margin-bottom: 8px;
}
.warn {
  color: #e6a23c;
}
.preview-panes {
  display: flex;
  gap: 12px;
  border: 1px solid var(--ws-border);
  border-radius: 6px;
  height: 380px;
}
.block-list {
  width: 340px;
  border-right: 1px solid var(--ws-border);
  overflow-y: auto;
}
.block-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  cursor: pointer;
  border-bottom: 1px solid var(--ws-border);
  font-size: 12px;
}
.block-item:hover,
.block-item.active {
  background: #ecf5ff;
}
.block-tok {
  color: var(--ws-text-light);
  width: 44px;
  text-align: right;
}
.block-section {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ws-text-light);
}
.block-detail {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}
.detail-meta {
  font-size: 12px;
  color: var(--ws-text-light);
  margin-bottom: 8px;
}
.detail-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  background: var(--ws-bg);
  padding: 10px;
  border-radius: 4px;
}
</style>
