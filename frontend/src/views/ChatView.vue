<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { chatStream } from '../api/sse'
import { errMsg, kbApi, locateApi, quizApi } from '../api/http'
import type { DocStatus, GradeItem, LocateItem, QuizQuestion } from '../api/http'
import type { Citation, DoneMeta } from '../api/types'

interface AssistantMessage {
  role: 'assistant'
  text: string
  citations: Citation[]
  meta: DoneMeta | null
  done: boolean
}

interface UserMessage {
  role: 'user'
  text: string
}

type Message = UserMessage | AssistantMessage

const route = useRoute()
const router = useRouter()
const kbId = Number(route.params.kbId)

const messages = reactive<Message[]>([])
const question = ref('')
const streaming = ref(false)
const listEl = ref<HTMLElement>()
const dialogSnippet = ref<Citation | null>(null)

// 查模式（原文定位）
const mode = ref<'ask' | 'locate' | 'quiz'>('ask')
const locateResults = ref<LocateItem[]>([])
const locating = ref(false)

// 练模式（出题判分）
const quizDocs = ref<DocStatus[]>([])
const quizDocId = ref('')
const quizSection = ref('')
const quizQuestions = ref<QuizQuestion[]>([])
const quizAnswers = reactive<string[]>([])
const quizGrades = ref<GradeItem[] | null>(null)
const quizing = ref(false)

async function loadQuizDocs() {
  if (quizDocs.value.length > 0) return
  try {
    quizDocs.value = (await kbApi.documents(kbId)).filter((d) => d.status === 'READY')
  } catch {
    /* 静默 */
  }
}

async function watchMode() {
  if (mode.value === 'quiz') await loadQuizDocs()
}

async function generateQuiz() {
  if (quizing.value) return
  quizing.value = true
  quizGrades.value = null
  try {
    quizQuestions.value = await quizApi.generate(kbId, quizDocId.value || undefined, quizSection.value || undefined)
    quizAnswers.length = 0
    quizQuestions.value.forEach(() => quizAnswers.push(''))
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    quizing.value = false
  }
}

async function submitQuiz() {
  if (quizing.value) return
  quizing.value = true
  try {
    quizGrades.value = await quizApi.grade(kbId, quizQuestions.value, [...quizAnswers])
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    quizing.value = false
  }
}

const canSend = computed(() => question.value.trim().length > 0 && !streaming.value)

/** [n] 角标转可点击上标（先转义 HTML 保证安全） */
function renderAnswer(text: string): string {
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped.replace(/\[(\d+)\]/g, '<sup class="cite" data-n="$1">[$1]</sup>')
}

function onCitationClick(event: MouseEvent) {
  const target = (event.target as HTMLElement).closest('.cite')
  if (!target) return
  const n = Number(target.getAttribute('data-n'))
  const last = [...messages].reverse().find((m) => m.role === 'assistant') as AssistantMessage | undefined
  const citation = last?.citations.find((c) => c.n === n)
  if (citation) dialogSnippet.value = citation
}

async function send() {
  const q = question.value.trim()
  if (!q || streaming.value) return

  if (mode.value === 'locate') {
    locating.value = true
    locateResults.value = []
    try {
      locateResults.value = await locateApi.query(kbId, q)
      if (locateResults.value.length === 0) {
        ElMessage.info('没有找到相关段落，换个说法试试')
      }
    } catch (e) {
      ElMessage.error(errMsg(e))
    } finally {
      locating.value = false
    }
    return
  }

  messages.push({ role: 'user', text: q })
  question.value = ''

  const assistant = reactive<AssistantMessage>({
    role: 'assistant',
    text: '',
    citations: [],
    meta: null,
    done: false,
  })
  messages.push(assistant)
  streaming.value = true
  await scrollBottom()

  await chatStream(kbId, q, {
    onCitation: (cs) => {
      assistant.citations = cs
    },
    onToken: (t) => {
      assistant.text += t
      void scrollBottom()
    },
    onDone: (meta) => {
      assistant.meta = meta
      assistant.done = true
    },
  }).catch(() => {
    assistant.meta = { error: '连接中断（后端可能已重启），请刷新后重试' }
    assistant.done = true
  })
  assistant.done = true
  streaming.value = false
}

async function scrollBottom() {
  await nextTick()
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}

function fillExample(q: string) {
  question.value = q
}

function onKeyEnter(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void send()
  }
}
</script>

<template>
  <div class="chat-page">
    <div class="mode-bar">
      <el-radio-group v-model="mode" size="small" @change="watchMode">
        <el-radio-button value="ask">问（AI 回答，带出处）</el-radio-button>
        <el-radio-button value="locate">查（只找原文段落）</el-radio-button>
        <el-radio-button value="quiz">练（自动出题判分）</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 练模式：出题与判分 -->
    <div v-if="mode === 'quiz'" class="quiz-wrap">
      <div class="quiz-scope">
        <el-select v-model="quizDocId" placeholder="出题范围：整个资料库" clearable style="width: 260px">
          <el-option v-for="d in quizDocs" :key="d.documentId" :label="d.fileName" :value="String(d.documentId)" />
        </el-select>
        <el-input v-model="quizSection" placeholder="章节（可选，如：第9章）" style="width: 200px" />
        <el-button type="primary" :loading="quizing" @click="generateQuiz">生成 5 道题</el-button>
      </div>

      <div class="quiz-list">
        <el-empty v-if="quizQuestions.length === 0" description="选好范围点生成——题目全部来自你上传的资料" />
        <el-card v-for="(q, qi) in quizQuestions" :key="qi" class="quiz-card" shadow="never">
          <div class="quiz-stem">{{ qi + 1 }}. [{{ q.type === 'single' ? '单选' : '简答' }}] {{ q.stem }}</div>
          <template v-if="q.type === 'single'">
            <el-radio-group v-model="quizAnswers[qi]" :disabled="!!quizGrades">
              <div v-for="opt in q.options" :key="opt" class="quiz-opt">
                <el-radio :value="opt.charAt(0)">{{ opt }}</el-radio>
              </div>
            </el-radio-group>
          </template>
          <el-input
            v-else
            v-model="quizAnswers[qi]"
            type="textarea"
            :rows="3"
            placeholder="用自己的话回答，判分会对照原文指出你漏掉的句子"
            :disabled="!!quizGrades"
          />
          <template v-if="quizGrades">
            <div class="quiz-grade" :class="{ good: quizGrades[qi].score >= 60 }">
              <template v-if="quizGrades[qi].correct !== null">
                {{ quizGrades[qi].correct ? '✓ 答对' : '✗ 答错' }} · {{ quizGrades[qi].comment }}
              </template>
              <template v-else>
                得分 {{ quizGrades[qi].score }}/100 · {{ quizGrades[qi].comment }}
              </template>
            </div>
            <div v-if="quizGrades[qi].missedSentences.length" class="quiz-missed">
              <div class="missed-title">你漏掉的原文：</div>
              <div v-for="(s, si) in quizGrades[qi].missedSentences" :key="si" class="missed-sentence">{{ s }}</div>
            </div>
          </template>
        </el-card>
      </div>

      <div v-if="quizQuestions.length" class="input-bar">
        <el-button v-if="!quizGrades" type="primary" :loading="quizing" @click="submitQuiz">交卷判分</el-button>
        <template v-else>
          <el-button type="primary" @click="generateQuiz">再来一组</el-button>
          <span class="quiz-total">
            总分 {{ quizGrades.reduce((a, g) => a + g.score, 0) / quizGrades.length }} / 100
          </span>
        </template>
      </div>
    </div>

    <!-- 查模式：段落卡片列表 -->
    <div v-else-if="mode === 'locate'" class="locate-wrap">
      <div ref="listEl" class="locate-list">
        <el-empty v-if="locateResults.length === 0 && !locating" description="丢一段话、一个术语、甚至半句记不全的话——直接定位到原文" />
        <el-card v-for="item in locateResults" :key="item.chunkId" class="locate-card" shadow="never">
          <div class="locate-src">
            {{ item.file }}<template v-if="item.page"> · 第{{ item.page }}页</template>
            <template v-if="item.section"> · {{ item.section }}</template>
            <span v-if="item.score" class="locate-score">相关度 {{ item.score }}</span>
          </div>
          <div class="locate-content">{{ item.content }}</div>
        </el-card>
      </div>
      <div class="input-bar">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          maxlength="500"
          placeholder="输入要找的内容（Enter 搜索）"
          :disabled="locating"
          @keydown="onKeyEnter"
        />
        <el-button type="primary" :disabled="!canSend" :loading="locating" @click="send">
          搜索
        </el-button>
      </div>
    </div>

    <!-- 问模式：对话 -->
    <template v-else>
    <div ref="listEl" class="msg-list" @click="onCitationClick">
      <div v-if="messages.length === 0" class="welcome">
        <div class="welcome-title">问点什么吧 📖</div>
        <div class="welcome-sub">答案只来自你上传的资料，每句话都标出处；资料里没有的会直说"没找到依据"</div>
        <div class="chips">
          <span class="chip" @click="fillExample('什么是二叉排序树？')">什么是二叉排序树？</span>
          <span class="chip" @click="fillExample('顺序表和链表插入删除的效率区别？')">顺序表和链表的区别？</span>
          <span class="chip" @click="fillExample('迪杰斯特拉算法求最短路径的步骤')">迪杰斯特拉的步骤</span>
          <span class="chip" @click="fillExample('起泡排序最坏情况要比较多少次？')">起泡排序最坏比较次数</span>
        </div>
      </div>
      <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
        <div class="bubble">
          <template v-if="m.role === 'user'">{{ m.text }}</template>
          <template v-else>
            <div class="answer" v-html="renderAnswer(m.text) || (streaming && i === messages.length - 1 ? '思考中…' : '')" />
            <div v-if="m.citations.length > 0" class="citations">
              <div class="cite-title">答案依据（点击角标或这里查看原文）</div>
              <div
                v-for="c in m.citations"
                :key="c.n"
                class="cite-item"
                @click="dialogSnippet = c"
              >
                <span class="cite-n">[{{ c.n }}]</span>
                <span class="cite-src">
                  {{ c.file }}<template v-if="c.page"> · 第{{ c.page }}页</template>
                  <template v-if="c.section"> · {{ c.section }}</template>
                </span>
              </div>
            </div>
            <div v-if="m.done && m.meta?.latencyMs" class="meta">
              {{ m.meta.latencyMs }}ms<template v-if="m.meta.completionTokens"> · {{ m.meta.completionTokens }} tokens</template>
              <template v-if="m.meta.error"> · {{ m.meta.error }}</template>
              <template v-if="m.meta.queryId && m.meta.queryId > 0">
                · <a class="debug-link" @click="router.push(`/debug/${kbId}?qid=${m.meta.queryId}`)">查看检索过程</a>
              </template>
            </div>
          </template>
        </div>
      </div>
    </div>

    <div class="input-bar">
      <el-input
        v-model="question"
        type="textarea"
        :rows="2"
        maxlength="500"
        placeholder="例如：什么是线性表？它和链表什么关系？  （Enter 发送，Shift+Enter 换行）"
        :disabled="streaming"
        @keydown="onKeyEnter"
      />
      <el-button type="primary" :disabled="!canSend" :loading="streaming" @click="send">
        发送
      </el-button>
    </div>
    </template>

    <el-dialog v-model="dialogSnippet" :title="dialogSnippet ? `[${dialogSnippet.n}] 原文片段` : ''" width="560px">
      <template v-if="dialogSnippet">
        <div class="dialog-src">
          {{ dialogSnippet.file }}
          <template v-if="dialogSnippet.page"> · 第 {{ dialogSnippet.page }} 页</template>
          <template v-if="dialogSnippet.section"> · {{ dialogSnippet.section }}</template>
        </div>
        <div class="dialog-content">{{ dialogSnippet.snippet }}</div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 140px);
  background: #fff;
  border: 1px solid var(--ws-border);
  border-radius: 10px;
}
.mode-bar {
  padding: 10px 12px 6px;
  background: linear-gradient(115deg, var(--ws-blue-soft), var(--ws-green-soft) 60%, var(--ws-orange-soft));
  border-radius: 10px 10px 0 0;
}
.mode-bar :deep(.el-radio-button__inner) {
  border: none;
  background: rgba(255, 255, 255, 0.72);
}
.welcome {
  text-align: center;
  padding: 44px 20px 20px;
}
.welcome-title {
  font-size: 20px;
  font-weight: 800;
  margin-bottom: 8px;
}
.welcome-sub {
  font-size: 13px;
  color: var(--ws-ink-light);
  margin-bottom: 18px;
}
.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: center;
}
.chip {
  padding: 6px 14px;
  border-radius: 999px;
  background: var(--ws-blue-soft);
  color: var(--ws-blue);
  font-size: 13px;
  cursor: pointer;
  border: 1px solid transparent;
}
.chip:hover {
  border-color: currentColor;
}
.chip:nth-child(2) {
  background: var(--ws-green-soft);
  color: var(--ws-green);
}
.chip:nth-child(3) {
  background: var(--ws-orange-soft);
  color: var(--ws-orange);
}
.chip:nth-child(4) {
  background: var(--ws-purple-soft);
  color: var(--ws-purple);
}
.quiz-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.quiz-scope {
  display: flex;
  gap: 10px;
  padding: 10px 14px;
}
.quiz-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 14px 14px;
}
.quiz-card {
  margin-bottom: 10px;
}
.quiz-stem {
  font-weight: 600;
  margin-bottom: 8px;
  line-height: 1.6;
}
.quiz-opt {
  padding: 2px 0;
}
.quiz-grade {
  margin-top: 8px;
  font-size: 13px;
  color: #f56c6c;
}
.quiz-grade.good {
  color: #67c23a;
}
.quiz-missed {
  margin-top: 6px;
  background: var(--ws-bg);
  border-left: 3px solid #e6a23c;
  padding: 8px 10px;
  border-radius: 4px;
}
.missed-title {
  font-size: 12px;
  color: #e6a23c;
  margin-bottom: 4px;
}
.missed-sentence {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ws-ink);
}
.quiz-total {
  align-self: center;
  font-size: 14px;
  font-weight: 600;
  color: var(--ws-blue);
}
.locate-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.locate-list {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
}
.locate-card {
  margin-bottom: 10px;
}
.locate-src {
  font-size: 12px;
  color: var(--ws-ink-light);
  margin-bottom: 6px;
}
.locate-score {
  float: right;
  color: var(--ws-blue);
}
.locate-content {
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.msg {
  display: flex;
  margin-bottom: 14px;
}
.msg.user {
  justify-content: flex-end;
}
.msg.user .bubble {
  background: var(--ws-blue);
  color: #fff;
  border-radius: 12px 12px 2px 12px;
  max-width: 70%;
  padding: 10px 14px;
  white-space: pre-wrap;
}
.msg.assistant .bubble {
  background: #faf8f3;
  border: 1px solid var(--ws-border);
  border-radius: 12px 12px 12px 2px;
  max-width: 86%;
  padding: 10px 14px;
}
.answer {
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.answer :deep(.cite) {
  color: var(--ws-blue);
  cursor: pointer;
  margin: 0 1px;
  font-weight: 600;
}
.citations {
  margin-top: 10px;
  border-top: 1px dashed var(--ws-border);
  padding-top: 8px;
}
.cite-title {
  font-size: 12px;
  color: var(--ws-ink-light);
  margin-bottom: 6px;
}
.cite-item {
  font-size: 12px;
  padding: 3px 0;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cite-item:hover {
  color: var(--ws-blue);
}
.cite-n {
  color: var(--ws-blue);
  font-weight: 600;
  margin-right: 6px;
}
.cite-src {
  color: var(--ws-ink-light);
}
.meta {
  margin-top: 6px;
  font-size: 11px;
  color: var(--ws-ink-light);
}
.debug-link {
  color: var(--ws-blue);
  cursor: pointer;
  text-decoration: underline;
}
.input-bar {
  display: flex;
  gap: 10px;
  padding: 12px;
  border-top: 1px solid var(--ws-border);
  align-items: flex-end;
}
.dialog-src {
  font-size: 13px;
  color: var(--ws-ink-light);
  margin-bottom: 8px;
}
.dialog-content {
  line-height: 1.8;
  background: var(--ws-bg);
  padding: 12px;
  border-radius: 6px;
}
</style>
