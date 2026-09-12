<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { debugApi, errMsg } from '../api/http'
import type { DebugCandidate, DebugResp } from '../api/http'

const route = useRoute()
const kbId = Number(route.params.kbId)

const question = ref('')
const loading = ref(false)
const result = ref<DebugResp | null>(null)

interface RecentRow {
  id: number
  question: string
  latency_ms: number
  created_at: string
}
const recent = ref<RecentRow[]>([])

const chosenSet = computed(() => new Set(result.value?.chosenIds ?? []))
const rowClass = ({ row }: { row: DebugCandidate }) =>
  chosenSet.value.has(row.chunkId) ? 'chosen-row' : ''
const maxMs = computed(() => {
  const m = result.value?.meta
  if (!m) return 1
  return Math.max(m.vectorMs, m.ftsMs, m.fuseMs, m.rerankMs, 1)
})

async function loadRecent() {
  try {
    recent.value = await debugApi.recent(kbId)
  } catch {
    /* 静默 */
  }
}

async function run() {
  const q = question.value.trim()
  if (!q || loading.value) return
  loading.value = true
  try {
    result.value = await debugApi.query(kbId, q)
    await loadRecent()
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

async function loadById(queryId: number) {
  loading.value = true
  try {
    result.value = await debugApi.byId(queryId)
    question.value = result.value.question
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadRecent()
  const qid = Number(route.query.qid)
  if (qid > 0) await loadById(qid)
})
</script>

<template>
  <div class="debug-page">
    <div class="page-head">
      <h2>检索调试台</h2>
      <span class="sub">每个答案背后的召回、打分与筛选全过程</span>
    </div>

    <el-card class="query-card">
      <div class="query-row">
        <el-input
          v-model="question"
          placeholder="输入问题，只跑检索不生成（不消耗模型 token）"
          maxlength="500"
          :disabled="loading"
          @keydown.enter="run"
        />
        <el-button type="primary" :loading="loading" @click="run">执行检索</el-button>
      </div>
      <div v-if="recent.length" class="recent">
        <span class="recent-label">最近：</span>
        <el-tag
          v-for="r in recent.slice(0, 8)"
          :key="r.id"
          class="recent-tag"
          effect="plain"
          @click="loadById(r.id)"
        >
          #{{ r.id }} {{ r.question.slice(0, 14) }}{{ r.question.length > 14 ? '…' : '' }}
        </el-tag>
      </div>
    </el-card>

    <template v-if="result">
      <el-card class="funnel-card">
        <template #header>召回漏斗</template>
        <div class="funnel">
          <div class="stage">
            <div class="stage-name">向量召回</div>
            <div class="stage-count">{{ result.meta?.vectorCount ?? '—' }}</div>
            <div class="stage-ms">{{ result.meta?.vectorMs ?? 0 }}ms</div>
          </div>
          <div class="arrow">→</div>
          <div class="stage">
            <div class="stage-name">关键词召回</div>
            <div class="stage-count">{{ result.meta?.ftsCount ?? '—' }}</div>
            <div class="stage-ms">{{ result.meta?.ftsMs ?? 0 }}ms</div>
          </div>
          <div class="arrow">→</div>
          <div class="stage">
            <div class="stage-name">RRF 融合</div>
            <div class="stage-count">{{ result.meta?.fusedCount ?? '—' }}</div>
            <div class="stage-ms">{{ result.meta?.fuseMs ?? 0 }}ms</div>
          </div>
          <div class="arrow">→</div>
          <div class="stage">
            <div class="stage-name">Rerank 精排</div>
            <div class="stage-count">{{ result.meta?.rerankCount ?? '—' }}</div>
            <div class="stage-ms">{{ result.meta?.rerankMs ?? 0 }}ms</div>
          </div>
          <div class="arrow">→</div>
          <div class="stage hot">
            <div class="stage-name">进入上下文</div>
            <div class="stage-count">{{ result.chosenIds.length }}</div>
            <div class="stage-ms">总 {{ result.latencyMs }}ms</div>
          </div>
        </div>
        <div v-if="result.meta" class="timing-bars">
          <div
            v-for="(ms, name) in { 向量: result.meta.vectorMs, 关键词: result.meta.ftsMs, 融合: result.meta.fuseMs, 精排: result.meta.rerankMs }"
            :key="name"
            class="bar-row"
          >
            <span class="bar-name">{{ name }}</span>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: Math.max((ms / maxMs) * 100, 1) + '%' }" />
            </div>
            <span class="bar-ms">{{ ms }}ms</span>
          </div>
        </div>
      </el-card>

      <el-card class="cand-card">
        <template #header>
          候选明细（{{ result.retrieved.length }} 条，按精排/融合序）
          <span class="hint">向量分=余弦相似度 · 关键词分=ts_rank · 融合分=RRF · 精排分=交叉编码器 sigmoid</span>
        </template>
        <el-table :data="result.retrieved" size="small" :row-class-name="rowClass">
          <el-table-column label="" width="70">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="内容摘要" min-width="320">
            <template #default="{ row }">
              <div class="snippet">{{ row.snippet }}</div>
              <div class="src">
                {{ row.file }}<template v-if="row.page"> · P{{ row.page }}</template>
                <template v-if="row.section"> · {{ row.section }}</template>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="vectorScore" label="向量" width="80">
            <template #default="{ row }">{{ row.vectorScore ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="ftsScore" label="关键词" width="80">
            <template #default="{ row }">{{ row.ftsScore ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="rrfScore" label="RRF" width="90">
            <template #default="{ row }">{{ row.rrfScore }}</template>
          </el-table-column>
          <el-table-column prop="rerankScore" label="精排" width="90">
            <template #default="{ row }">
              <b v-if="row.rerankScore != null" :class="{ hi: (row.rerankScore ?? 0) >= 0.35 }">{{ row.rerankScore }}</b>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="入选" width="70">
            <template #default="{ row }">
              <el-tag v-if="chosenSet.has(row.chunkId)" type="success" size="small">✓</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="result.answer" class="ans-card">
        <template #header>该查询的最终回答（queryId={{ result.queryId }}）</template>
        <div class="answer">{{ result.answer }}</div>
      </el-card>
    </template>
    <el-empty v-else-if="!loading" description="输入问题执行一次检索，或点击最近查询回看" />
  </div>
</template>

<script lang="ts">
export default { name: 'DebugView' }
</script>

<style scoped>
.debug-page {
  max-width: 1080px;
  margin: 0 auto;
}
.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 14px;
}
.page-head h2 {
  margin: 0;
}
.sub {
  color: var(--ws-text-light);
  font-size: 13px;
}
.query-card {
  margin-bottom: 14px;
}
.query-row {
  display: flex;
  gap: 10px;
}
.recent {
  margin-top: 10px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
}
.recent-label {
  font-size: 12px;
  color: var(--ws-text-light);
}
.recent-tag {
  cursor: pointer;
}
.funnel-card,
.cand-card {
  margin-bottom: 14px;
}
.funnel {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.stage {
  border: 1px solid var(--ws-border);
  border-radius: 8px;
  padding: 10px 16px;
  text-align: center;
  min-width: 96px;
}
.stage.hot {
  border-color: var(--ws-primary);
  background: #ecf5ff;
}
.stage-name {
  font-size: 12px;
  color: var(--ws-text-light);
}
.stage-count {
  font-size: 22px;
  font-weight: 700;
  margin: 2px 0;
}
.stage-ms {
  font-size: 11px;
  color: var(--ws-text-light);
}
.arrow {
  color: var(--ws-text-light);
}
.timing-bars {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.bar-name {
  width: 48px;
  font-size: 12px;
  color: var(--ws-text-light);
  text-align: right;
}
.bar-track {
  flex: 1;
  background: var(--ws-bg);
  border-radius: 4px;
  height: 12px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  background: var(--ws-primary);
  border-radius: 4px;
}
.bar-ms {
  width: 70px;
  font-size: 12px;
  color: var(--ws-text-light);
}
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--ws-text-light);
  font-weight: 400;
}
.snippet {
  font-size: 12px;
  line-height: 1.5;
}
.src {
  font-size: 11px;
  color: var(--ws-text-light);
  margin-top: 2px;
}
.hi {
  color: var(--ws-primary);
}
:deep(.chosen-row) {
  background: #f0f9eb;
}
.ans-card .answer {
  white-space: pre-wrap;
  line-height: 1.8;
}
</style>
