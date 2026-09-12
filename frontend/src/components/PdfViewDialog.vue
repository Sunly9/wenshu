<script setup lang="ts">
import { ref, watch } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import PdfWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?worker'
import { getVisitorId } from '../api/visitor'

pdfjsLib.GlobalWorkerOptions.workerPort = new PdfWorker()

interface TextItem {
  str: string
  x: number
  y: number
  w: number
  h: number
  highlighted: boolean
}

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
const items = ref<TextItem[]>([])
const pageScale = ref(1)
const pageHeight = ref(1000)
const status = ref('idle')
const highlightTop = ref(0)
const pageEl = ref<HTMLDivElement>()

let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null

watch(
  () => [props.visible, props.docId] as const,
  async ([visible, docId]) => {
    if (!visible || !docId) return
    loading.value = true
    status.value = '加载资料…'
    try {
      const doc = pdfDoc as unknown as { destroy?: () => Promise<void> }
      await doc?.destroy?.()
      pdfDoc = await pdfjsLib.getDocument({
        url: `/api/documents/${docId}/file`,
        httpHeaders: { 'X-Visitor-Id': getVisitorId() },
      }).promise
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
  if (!pdfDoc) return
  const page = await pdfDoc.getPage(pageNum.value)
  const base = page.getViewport({ scale: 1 })
  const scale = 760 / base.width
  pageScale.value = scale
  pageHeight.value = base.height * scale

  const content = await page.getTextContent()
  const key = props.snippet.replace(/\s+/g, '').slice(0, 14)
  const list: TextItem[] = []
  let acc = ''
  let matched = false
  for (const raw of content.items as Array<{ str: string; transform: number[]; width: number; height: number }>) {
    if (!raw.str || !raw.str.trim()) continue
    acc += raw.str.replace(/\s+/g, '')
    const hitNow = !matched && key && acc.length >= key.length && acc.includes(key)
    if (hitNow) matched = true
    const x = raw.transform[4] * scale
    const y = (base.height - raw.transform[5]) * scale
    const h = Math.max(raw.height * scale, 11)
    list.push({
      str: raw.str,
      x,
      y: y - h + scale * 3,
      w: raw.width * scale,
      h,
      highlighted: hitNow || undefined as never,
    })
    if (hitNow) highlightTop.value = y
  }
  // 命中项可能横跨多个 item：把从命中起点累计覆盖 key 长度的都标上
  if (key && matched) {
    let seen = 0
    let marking = false
    for (const item of list) {
      if (!marking && (item as TextItem & { highlighted?: boolean }).highlighted) marking = true
      if (marking) {
        (item as TextItem & { highlighted?: boolean }).highlighted = true
        seen += item.str.replace(/\s+/g, '').length
        if (seen >= key.length + 4) break
      }
    }
  }
  items.value = list
  setTimeout(() => {
    pageEl.value?.querySelector('.hl')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, 100)
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
      这是该页资料的<b>原文文字</b>（按原版式还原），🟡 黄色高亮处就是这条答案依据的位置——答案不是编的，出处在这里。
    </div>
    <div v-loading="loading" class="pdf-wrap" :data-status="status">
      <div class="pdf-toolbar">
        <el-button size="small" :disabled="pageNum <= 1" @click="go(-1)">上一页</el-button>
        <span>第 {{ pageNum }} / {{ pageCount }} 页</span>
        <el-button size="small" :disabled="pageNum >= pageCount" @click="go(1)">下一页</el-button>
        <span v-if="status.startsWith('error')" class="status-tag">{{ status }}</span>
      </div>
      <div class="text-page-wrap">
        <div ref="pageEl" class="text-page" :style="{ height: pageHeight + 'px' }">
          <span
            v-for="(it, i) in items"
            :key="i"
            class="ti"
            :class="{ hl: (it as any).highlighted }"
            :style="{ left: it.x + 'px', top: it.y + 'px', fontSize: it.h + 'px', lineHeight: it.h + 'px' }"
          >{{ it.str }}</span>
        </div>
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
  color: #f56c6c;
  font-size: 11px;
}
.text-page-wrap {
  overflow-y: auto;
  max-height: 64vh;
  border: 1px solid var(--ws-border);
  border-radius: 8px;
  background: #fffdf7;
}
.text-page {
  position: relative;
  width: 760px;
  margin: 0 auto;
  font-family: 'SimSun', 'Songti SC', serif;
  color: #2b2b2b;
}
.ti {
  position: absolute;
  white-space: pre;
  transform-origin: left top;
}
.ti.hl {
  background: rgba(255, 213, 79, 0.55);
  border-radius: 3px;
  outline: 1px solid #f0b429;
}
</style>
