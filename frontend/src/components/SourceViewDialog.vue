<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { http, errMsg, shortSection } from '../api/http'

/** 原文阅读卡：点引用/定位结果后的落地页——舒服地读到出处原文，确认答案有据 */
const props = defineProps<{ visible: boolean; chunkId: number | null }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

interface ChunkRow {
  id: number
  file_name: string
  page_no: number | null
  section_path: string | null
  content: string
  parentContent?: string | null
  prevContent?: string | null
  nextContent?: string | null
}

const loading = ref(false)
const row = ref<ChunkRow | null>(null)
const showContext = ref(false)

watch(
  () => [props.visible, props.chunkId] as const,
  async ([visible, chunkId]) => {
    if (!visible || !chunkId) return
    loading.value = true
    row.value = null
    showContext.value = false
    try {
      row.value = (await http.get<ChunkRow>(`/chunks/${chunkId}`)).data
    } catch (e) {
      errMsg(e)
    } finally {
      loading.value = false
    }
  },
)

/** 依据块在父块正文中高亮：能定位则整段高亮该子串，否则父块整段淡显 */
const parentParagraphs = computed(() => {
  if (!row.value?.parentContent) return []
  const child = row.value.content.replace(/\s+/g, '')
  const key = child.slice(0, 30)
  return row.value.parentContent
    .split(/\n{1,}/)
    .filter((p) => p.trim())
    .map((p) => {
      const flat = p.replace(/\s+/g, '')
      const idx = flat.indexOf(key.slice(0, 20))
      return { text: p, hit: key && idx >= 0 && idx < 5 }
    })
})
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="答案依据的原文"
    width="680px"
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-loading="loading" class="source-card">
      <template v-if="row">
        <div class="meta">
          📄 {{ row.file_name }}
          <template v-if="row.page_no"> · 第 {{ row.page_no }} 页</template>
          <template v-if="row.section_path"> · {{ shortSection(row.section_path) }}</template>
        </div>

        <div class="explain">AI 回答里这条结论的原文依据就在下面——<b>黄底是依据段落，答案不是编的</b>。</div>

        <!-- 依据块全文（永远完整展示） -->
        <div class="basis">
          <div class="basis-label">🟡 本条依据（原文）</div>
          <div class="basis-text">{{ row.content }}</div>
        </div>

        <!-- 所在段落上下文 -->
        <div v-if="parentParagraphs.length" class="around">
          <div class="around-label">它在这一段里：</div>
          <p
            v-for="(p, i) in parentParagraphs"
            :key="i"
            class="para"
            :class="{ hit: p.hit }"
          >{{ p.text }}</p>
        </div>

        <!-- 前后文折叠 -->
        <el-collapse v-if="row.prevContent || row.nextContent" v-model="showContext" class="ctx">
          <el-collapse-item title="看看这页前后还讲了什么" name="ctx">
            <div v-if="row.prevContent" class="ctx-block">
              <div class="ctx-tag">上一段</div>
              <p class="para dim">{{ row.prevContent }}</p>
            </div>
            <div v-if="row.nextContent" class="ctx-block">
              <div class="ctx-tag">下一段</div>
              <p class="para dim">{{ row.nextContent }}</p>
            </div>
          </el-collapse-item>
        </el-collapse>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.source-card {
  min-height: 120px;
}
.meta {
  font-size: 13px;
  color: var(--ws-ink-light);
  margin-bottom: 10px;
}
.explain {
  background: var(--ws-yellow-soft);
  border: 1px solid #eadfb8;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.7;
  margin-bottom: 14px;
}
.basis {
  background: rgba(255, 213, 79, 0.28);
  border-left: 4px solid #f0b429;
  border-radius: 0 8px 8px 0;
  padding: 12px 14px;
  margin-bottom: 14px;
}
.basis-label {
  font-size: 12px;
  color: #b8860b;
  margin-bottom: 6px;
}
.basis-text {
  font-size: 15px;
  line-height: 2;
  white-space: pre-wrap;
}
.around-label,
.ctx-tag {
  font-size: 12px;
  color: var(--ws-ink-light);
  margin-bottom: 6px;
}
.para {
  font-size: 14px;
  line-height: 2;
  color: var(--ws-ink);
  margin: 0 0 8px;
  text-indent: 0;
}
.para.hit {
  background: rgba(255, 213, 79, 0.35);
  border-radius: 4px;
  padding: 2px 4px;
}
.para.dim {
  color: #7a756a;
}
.ctx-block {
  margin-bottom: 10px;
}
</style>
