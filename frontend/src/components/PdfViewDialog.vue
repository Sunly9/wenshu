<script setup lang="ts">
import { ref, watch } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import PdfWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?worker'
import { getVisitorId } from '../api/visitor'

// Vite 官方姿势：?worker 直接构造 Worker 实例，避免 workerSrc 路径在 dev/prod 不一致
pdfjsLib.GlobalWorkerOptions.workerPort = new PdfWorker()

const props = defineProps<{
  visible: boolean
  docId: number | null
  page: number | null
  snippet: string
  fileName?: string
}>()

const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const loading = ref(false)
const pageNum = ref(1)
const pageCount = ref(0)
const canvasEl = ref<HTMLCanvasElement>()
const highlightEl = ref<HTMLDivElement>()
const status = ref('idle')  // 调试用：idle/loading/ok/错误信息

let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null

watch(
  () => [props.visible, props.docId] as const,
  async ([visible, docId]) => {
    if (!visible || !docId) return
    loading.value = true
    status.value = 'loading doc ' + docId
    try {
      highlightEl.value?.style.setProperty('display', 'none')
      // pdfjs 运行时有 destroy，类型定义版本不一致，这里安全调用
      const doc = pdfDoc as unknown as { destroy?: () => Promise<void> }
      await doc?.destroy?.()
      status.value = 'getDocument…'
      pdfDoc = await pdfjsLib.getDocument({
        url: `/api/documents/${docId}/file`,
        httpHeaders: { 'X-Visitor-Id': getVisitorId() },
      }).promise
      status.value = 'pages:' + pdfDoc.numPages
      pageCount.value = pdfDoc.numPages
      pageNum.value = Math.min(Math.max(props.page ?? 1, 1), pdfDoc.numPages)
      await renderPage()
      status.value = 'ok'
    } catch (e) {
      status.value = 'error: ' + (e instanceof Error ? e.message : String(e))
    } finally {
      loading.value = false
    }
  },
)

watch(
  () => props.page,
  (p) => {
    if (props.visible && p && p !== pageNum.value && p <= pageCount.value) {
      pageNum.value = p
      void renderPage()
    }
  },
)

async function go(delta: number) {
  const next = pageNum.value + delta
  if (next < 1 || next > pageCount.value) return
  pageNum.value = next
  await renderPage()
}

async function renderPage() {
  if (!pdfDoc || !canvasEl.value) return
  const page = await pdfDoc.getPage(pageNum.value)
  const width = 760
  const viewport = page.getViewport({ scale: width / page.getViewport({ scale: 1 }).width })
  const canvas = canvasEl.value
  canvas.width = viewport.width
  canvas.height = viewport.height
  const context = canvas.getContext('2d')
  context?.clearRect(0, 0, canvas.width, canvas.height)
  // 渲染放后台：某些版本 render().promise 不 resolve 但绘制已完成，不阻塞高亮逻辑
  type RenderArgs = Parameters<pdfjsLib.PDFPageProxy['render']>[0]
  void page.render({ canvasContext: context, viewport } as unknown as RenderArgs).promise.catch(() => undefined)

  await applyHighlight(page, viewport)
}

async function applyHighlight(
  page: pdfjsLib.PDFPageProxy,
  viewport: ReturnType<pdfjsLib.PDFPageProxy['getViewport']>,
) {
  if (!props.snippet || !highlightEl.value) return
  const key = props.snippet.replace(/\s+/g, '').slice(0, 14)
  if (!key) return
  const content = await page.getTextContent()
  let acc = ''
  for (const item of content.items as Array<{ str: string; transform: number[]; width: number; height: number }>) {
    acc += item.str.replace(/\s+/g, '')
    if (acc.length >= key.length && acc.includes(key)) {
      const startItem = item
      const tx = startItem.transform[4]
      const ty = startItem.transform[5]
      const base = page.getViewport({ scale: 1 })
      const scale = viewport.scale
      const x = tx * scale
      const y = (base.height - ty) * scale
      const h = Math.max(startItem.height * scale, 14)
      const box = highlightEl.value!
      box.style.display = 'block'
      box.style.left = Math.max(x - 4, 0) + 'px'
      box.style.top = Math.max(y - h + 2, 0) + 'px'
      box.style.width = Math.min(startItem.width * scale + 40, 560) + 'px'
      box.style.height = h + 'px'
      box.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return
    }
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="(fileName ? fileName.slice(0, 28) : '资料') + (page ? ` · 第 ${page} 页` : '')"
    width="820px"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="pdf-explain">
      这是资料的<b>原始 PDF 页面</b>，🟡 黄色高亮处就是这条答案依据在书里的位置——答案不是编的，出处在这里。
    </div>
    <div v-loading="loading" class="pdf-wrap" :data-status="status">
      <div class="pdf-toolbar">
        <span v-if="status.startsWith('error')" class="status-tag">{{ status }}</span>
        <el-button size="small" :disabled="pageNum <= 1" @click="go(-1)">上一页</el-button>
        <span>第 {{ pageNum }} / {{ pageCount }} 页</span>
        <el-button size="small" :disabled="pageNum >= pageCount" @click="go(1)">下一页</el-button>
        <span v-if="snippet" class="hint">🟡 高亮段落为本条答案依据</span>
      </div>
      <div class="pdf-canvas-wrap">
        <canvas ref="canvasEl" />
        <div ref="highlightEl" class="pdf-highlight" />
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.pdf-explain {
  background: var(--ws-yellow-soft);
  border: 1px solid #eadfb8;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.7;
  margin-bottom: 10px;
}
.pdf-wrap {
  display: flex;
  flex-direction: column;
}
.pdf-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--ws-ink-light);
}
.status-tag {
  font-size: 11px;
  color: var(--ws-purple);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hint {
  margin-left: auto;
  font-size: 12px;
}
.pdf-canvas-wrap {
  position: relative;
  overflow: auto;
  max-height: 64vh;
  border: 1px solid var(--ws-border);
  border-radius: 8px;
  background: #525659;
}
.pdf-canvas-wrap canvas {
  display: block;
}
.pdf-highlight {
  display: none;
  position: absolute;
  background: rgba(255, 213, 79, 0.45);
  border: 1.5px solid #f0b429;
  border-radius: 4px;
  pointer-events: none;
}
</style>
