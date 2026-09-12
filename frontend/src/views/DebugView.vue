<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { debugApi, errMsg, evalApi } from '../api/http'
import type { DebugCandidate, DebugResp, EvalRow, EvalStats, EvalType } from '../api/http'

const route = useRoute()
const kbId = Number(route.params.kbId)

const question = ref('')
const loading = ref(false)
const result = ref<DebugResp | null>(null)
const strategy = ref<string>('')

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
    result.value = await debugApi.query(kbId, q, strategy.value || undefined)
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
  await loadEvalStats()
  const qid = Number(route.query.qid)
  if (qid > 0) await loadById(qid)
})

// ---------- 评测集标注 ----------
// ---------- 评测面板（消融实验） ----------
const activeTab = ref('debug')
const runsRaw = ref<Awaited<ReturnType<typeof evalApi.runs>>>([])
const ablationRunning = ref(false)
let runTimer: number | undefined

const evalRuns = computed(() =>
  runsRaw.value
    .map((r) => {
      let config: { name?: string } = {}
      let detail: { byType?: Record<string, { hitRate: number }>; refusalRate?: number } = {}
      try {
        config = JSON.parse(r.config)
      } catch {
        /* ignore */
      }
      try {
        detail = JSON.parse(r.detail)
      } catch {
        /* ignore */
      }
      return { ...r, configName: config.name ?? '?', detail }
    })
    .sort((a, b) => (a.configName > b.configName ? 1 : -1)),
)

async function loadRuns() {
  try {
    runsRaw.value = await evalApi.runs(kbId)
  } catch {
    /* 静默 */
  }
}

async function runAblation() {
  if (ablationRunning.value) return
  ablationRunning.value = true
  activeTab.value = 'eval'
  try {
    await evalApi.run(kbId)
    runTimer = window.setInterval(async () => {
      await loadRuns()
      if (runsRaw.value.length >= 6) {
        window.clearInterval(runTimer)
        ablationRunning.value = false
        ElMessage.success('消融跑批完成（6 配置）')
      }
    }, 8000)
  } catch (e) {
    ablationRunning.value = false
    ElMessage.error(errMsg(e))
  }
}

const genRunning = ref(false)

async function runGenerationEval() {
  if (genRunning.value) return
  genRunning.value = true
  activeTab.value = 'eval'
  const before = runsRaw.value.length
  try {
    await evalApi.generationRun(kbId)
    runTimer = window.setInterval(async () => {
      await loadRuns()
      if (runsRaw.value.length > before) {
        window.clearInterval(runTimer)
        genRunning.value = false
        ElMessage.success('生成质量评测完成')
      }
    }, 15000)
  } catch (e) {
    genRunning.value = false
    ElMessage.error(errMsg(e))
  }
}

onMounted(loadRuns)

const evalStats = ref<EvalStats | null>(null)
const evalRows = ref<EvalRow[]>([])
const evalDialog = ref(false)
const evalType = ref<EvalType>('FACT')
const evalAnswer = ref('')
const evalGold = ref<number[]>([])
const savingEval = ref(false)

async function loadEvalStats() {
  try {
    evalStats.value = await evalApi.stats(kbId)
  } catch {
    /* 静默 */
  }
}

function openEvalDialog() {
  if (!result.value) return
  evalType.value = 'FACT'
  evalAnswer.value = ''
  evalGold.value = result.value.retrieved
    .filter((c) => result.value?.chosenIds.includes(c.chunkId))
    .map((c) => c.chunkId)
  evalDialog.value = true
}

async function saveEval() {
  if (savingEval.value) return
  savingEval.value = true
  try {
    evalStats.value = await evalApi.add(kbId, {
      question: result.value!.question,
      type: evalType.value,
      goldChunkIds: evalType.value === 'NO_ANSWER' ? [] : evalGold.value,
      goldAnswer: evalAnswer.value,
    })
    ElMessage.success(`已保存（${evalStats.value.total}/50）`)
    evalDialog.value = false
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    savingEval.value = false
  }
}

async function loadEvalRows() {
  try {
    evalRows.value = await evalApi.list(kbId)
  } catch {
    /* 静默 */
  }
}

async function removeEval(id: number) {
  try {
    evalStats.value = await evalApi.remove(kbId, id)
    await loadEvalRows()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}
</script>

<template>
  <div class="debug-page">
    <div class="page-head">
      <h2>检索调试台</h2>
      <span class="sub">每个答案背后的召回、打分与筛选全过程</span>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="检索调试" name="debug">
    <el-card class="query-card">
      <div class="query-row">
        <el-select v-model="strategy" style="width: 190px" placeholder="分块策略">
          <el-option label="库当前策略" value="" />
          <el-option label="结构感知" value="STRUCTURE_AWARE" />
          <el-option label="递归分隔符" value="RECURSIVE" />
          <el-option label="固定长度" value="FIXED" />
        </el-select>
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
    <el-card v-if="result" class="funnel-card">
      <template #header>
        召回漏斗
        <el-button class="save-eval-btn" size="small" type="primary" plain @click="openEvalDialog">
          存为评测题
        </el-button>
      </template>
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

    <!-- 评测集标注进度 -->
    <el-card class="eval-card">
      <template #header>
        评测集标注（{{ evalStats?.total ?? 0 }}/50）
        <el-button size="small" text @click="evalRows.length ? (evalRows = []) : loadEvalRows()">
          {{ evalRows.length ? '收起' : '查看已标注' }}
        </el-button>
      </template>
      <div class="eval-stats">
        <el-tag>事实型 {{ evalStats?.FACT ?? 0 }}/25</el-tag>
        <el-tag type="warning">多跳型 {{ evalStats?.MULTI_HOP ?? 0 }}/10</el-tag>
        <el-tag type="success">表格型 {{ evalStats?.TABLE ?? 0 }}/8</el-tag>
        <el-tag type="info">无答案 {{ evalStats?.NO_ANSWER ?? 0 }}/7</el-tag>
      </div>
      <el-table v-if="evalRows.length" :data="evalRows" size="small">
        <el-table-column prop="type" label="题型" width="110" />
        <el-table-column prop="question" label="问题" min-width="260" show-overflow-tooltip />
        <el-table-column label="gold 块" width="100">
          <template #default="{ row }">{{ (row.gold_chunk_ids || []).length }}</template>
        </el-table-column>
        <el-table-column label="" width="70">
          <template #default="{ row }">
            <el-button text type="danger" size="small" @click="removeEval(row.id)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
      </el-tab-pane>

      <el-tab-pane label="评测面板（消融实验）" name="eval">
        <div class="ablation-head">
          <el-button type="primary" :loading="ablationRunning" @click="runAblation">
            一键跑消融实验（6 配置 × {{ evalStats?.total ?? 50 }} 题）
          </el-button>
          <el-button :loading="genRunning" @click="runGenerationEval">跑生成质量评测（LLM 裁判）</el-button>
          <span v-if="ablationRunning || genRunning" class="ablation-tip">跑批中…结果逐条出现，可稍后刷新</span>
        </div>
        <el-table :data="evalRuns" size="small" v-loading="ablationRunning || genRunning">
          <el-table-column prop="configName" label="配置" min-width="180" />
          <el-table-column label="HitRate@5" width="100">
            <template #default="{ row }">
              {{ row.hit_rate_at5 != null ? (row.hit_rate_at5 * 100).toFixed(1) + '%' : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="MRR" width="80">
            <template #default="{ row }">
              {{ row.mrr != null ? row.mrr.toFixed(3) : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="引用准确率" width="100">
            <template #default="{ row }">
              {{ row.citation_accuracy != null ? (row.citation_accuracy * 100).toFixed(1) + '%' : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="忠实度" width="90">
            <template #default="{ row }">
              {{ row.faithfulness != null ? (row.faithfulness * 100).toFixed(1) + '%' : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="事实型" width="90">
            <template #default="{ row }">
              {{ row.detail.byType?.FACT ? (row.detail.byType.FACT.hitRate * 100).toFixed(0) + '%' : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="多跳型" width="90">
            <template #default="{ row }">
              {{ row.detail.byType?.MULTI_HOP ? (row.detail.byType.MULTI_HOP.hitRate * 100).toFixed(0) + '%' : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="表格型" width="90">
            <template #default="{ row }">
              {{ row.detail.byType?.TABLE ? (row.detail.byType.TABLE.hitRate * 100).toFixed(0) + '%' : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="正确拒答率" width="100">
            <template #default="{ row }">
              {{ row.detail.refusalRate != null ? (row.detail.refusalRate * 100).toFixed(0) + '%' : '—' }}
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="evalRuns.length === 0 && !ablationRunning" description="标注完成后一键跑批，产出消融对比表" />
      </el-tab-pane>
    </el-tabs>

    <!-- 存为评测题对话框 -->
    <el-dialog v-model="evalDialog" title="存为评测题" width="560px">
      <div class="eval-q">问题：{{ result?.question }}</div>
      <el-form label-width="80px">
        <el-form-item label="题型">
          <el-select v-model="evalType">
            <el-option label="事实型（单点知识）" value="FACT" />
            <el-option label="多跳型（跨章节）" value="MULTI_HOP" />
            <el-option label="表格型（教材表格数据）" value="TABLE" />
            <el-option label="无答案（资料里没有，期望拒答）" value="NO_ANSWER" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="evalType !== 'NO_ANSWER'" label="标准块">
          <el-checkbox-group v-model="evalGold">
            <el-checkbox v-for="c in result?.retrieved ?? []" :key="c.chunkId" :value="c.chunkId">
              #{{ c.chunkId }} {{ c.snippet.slice(0, 28) }}…
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="答案要点">
          <el-input v-model="evalAnswer" type="textarea" :rows="2" placeholder="参考答案要点（给 D19 裁判用，可简写）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="evalDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingEval" @click="saveEval">保存</el-button>
      </template>
    </el-dialog>
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
.ablation-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.ablation-tip {
  font-size: 12px;
  color: var(--ws-text-light);
}
.save-eval-btn {
  float: right;
  margin-top: -6px;
}
.eval-card {
  margin-bottom: 14px;
}
.eval-stats {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.eval-q {
  font-weight: 600;
  margin-bottom: 12px;
  line-height: 1.6;
}
</style>
