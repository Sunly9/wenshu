<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { chatStream } from '../api/sse'
import { errMsg, kbApi, locateApi, quizApi, suggestApi, shortSection, chatApi,
         conversationApi, wrongApi } from '../api/http'
import type { Conversation } from '../api/http'
import { isDevMode } from '../api/visitor'
import SourceViewDialog from '../components/SourceViewDialog.vue'
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
const devMode = ref(isDevMode())

// 示例问题：按库内容动态生成
const suggestions = ref<string[]>([])
const suggestLoading = ref(false)

async function loadSuggestions() {
  if (suggestions.value.length > 0) return
  suggestLoading.value = true
  try {
    suggestions.value = await suggestApi.questions(kbId)
  } catch {
    /* 静默，空状态不显示 chips */
  } finally {
    suggestLoading.value = false
  }
}
loadSuggestions()

const messages = reactive<Message[]>([])
const question = ref('')
const streaming = ref(false)
const listEl = ref<HTMLElement>()
const dialogSnippet = ref<Citation | null>(null)

// 学习模式开关（严格=只从资料回答 / 学习=从资料出发深入讲解）
const chatMode = ref<'strict' | 'learn'>('strict')

// ---------- 对话管理（历史对话保存/切换） ----------
const conversations = ref<Conversation[]>([])
const currentConversationId = ref<number | null>(null)
const showConversationList = ref(true)

async function loadConversations() {
  try {
    conversations.value = await conversationApi.list(kbId)
  } catch { /* 静默 */ }
}

async function newConversation() {
  const conv = await conversationApi.create(kbId)
  currentConversationId.value = conv.id
  messages.length = 0  // 清空当前消息
  await loadConversations()
}

async function switchConversation(id: number) {
  if (id === currentConversationId.value) return
  currentConversationId.value = id
  messages.length = 0
  const msgs = await conversationApi.messages(id)
  for (const m of msgs) {
    if (m.role === 'user') {
      messages.push({ role: 'user', text: m.content })
    } else {
      let citations: Citation[] = []
      try { citations = m.citations ? JSON.parse(m.citations) : [] } catch { /* ignore */ }
      messages.push({ role: 'assistant', text: m.content, citations, meta: null, done: true })
    }
  }
  await scrollBottom()
}

async function deleteConversation(id: number) {
  await conversationApi.delete(id)
  if (currentConversationId.value === id) {
    currentConversationId.value = null
    messages.length = 0
  }
  await loadConversations()
}

// ---------- 错题删除 ----------
async function deleteWrong(attemptId: number, questionIndex: number) {
  try {
    await wrongApi.delete(kbId, attemptId, questionIndex)
    ElMessage.success('已标记为掌握')
    await loadWrong()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

// 学习计划
interface StudyPlanData {
  overview?: string
  chapters?: Array<{ title: string; priority: string; focus: string; estimated_minutes: number }>
  tips?: string[]
}
const studyPlan = ref<StudyPlanData | string | null>(null)
const planLoading = ref(false)

async function generateStudyPlan() {
  if (planLoading.value) return
  planLoading.value = true
  try {
    const raw = await chatApi.studyPlan(kbId)
    // 后端返回 JSON 字符串，解析为可显示的结构
    try {
      const plan = JSON.parse(raw)
      studyPlan.value = plan
    } catch {
      studyPlan.value = raw
    }
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    planLoading.value = false
  }
}

// 查模式（原文定位）
const mode = ref<'ask' | 'locate' | 'quiz'>('ask')
const locateResults = ref<LocateItem[]>([])
const locating = ref(false)

// 练模式（出题判分 + 错题本）
const quizDocs = ref<DocStatus[]>([])
const quizDocId = ref('')
const quizSection = ref('')
const quizQuestions = ref<QuizQuestion[]>([])
const quizAnswers = reactive<string[]>([])
const quizGrades = ref<GradeItem[] | null>(null)
const quizing = ref(false)
const quizTab = ref<'practice' | 'wrong'>('practice')
const wrongList = ref<Awaited<ReturnType<typeof quizApi.wrong>>>([])
const wrongLoading = ref(false)

async function loadQuizDocs() {
  if (quizDocs.value.length > 0) return
  try {
    quizDocs.value = (await kbApi.documents(kbId)).filter((d) => d.status === 'READY')
  } catch {
    /* 静默 */
  }
}

async function loadWrong() {
  wrongLoading.value = true
  try {
    wrongList.value = await quizApi.wrong(kbId)
  } catch {
    /* 静默 */
  } finally {
    wrongLoading.value = false
  }
}

function watchQuizTab() {
  if (quizTab.value === 'wrong' && wrongList.value.length === 0) void loadWrong()
}

function parseOptions(raw: string | undefined): string[] {
  if (!raw) return []
  try {
    return JSON.parse(raw)
  } catch {
    return []
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
    // 交卷自动存档（错题本数据源）
    if (quizGrades.value) {
      quizApi.saveAttempt(kbId, quizQuestions.value, [...quizAnswers], quizGrades.value).catch(() => undefined)
    }
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
  if (citation) {
    if (citation.chunkId) {
      sourceChunkId.value = citation.chunkId
      sourceView.value = true
    } else {
      dialogSnippet.value = citation
    }
  }
}

// 原文阅读卡：点引用/定位结果 → 舒服地读到出处原文
const sourceView = ref(false)
const sourceChunkId = ref<number | null>(null)

function openSource(chunkId: number | undefined | null) {
  if (chunkId) {
    sourceChunkId.value = chunkId
    sourceView.value = true
  }
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

  // 构建对话历史（最近3轮，支持追问）
  const history = messages
    .filter((m) => m.role === 'user' || (m.role === 'assistant' && m.done && m.text))
    .slice(-6)
    .map((m) => ({
      role: m.role === 'user' ? 'user' : 'assistant',
      content: m.role === 'user' ? m.text : m.text.slice(0, 200),
    }))

  // 如果没有当前对话，自动创建一个
  if (!currentConversationId.value) {
    try {
      const conv = await conversationApi.create(kbId)
      currentConversationId.value = conv.id
      loadConversations() // 后台刷新列表
    } catch { /* 对话创建失败不阻塞提问 */ }
  }

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
      loadConversations() // 刷新对话列表（标题可能更新了）
    },
  }, chatMode.value, history, currentConversationId.value || undefined).catch(() => {
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

    <!-- 练模式：出题与判分 + 错题本 -->
    <div v-if="mode === 'quiz'" class="quiz-wrap">
      <div class="quiz-tabs">
        <el-radio-group v-model="quizTab" size="small" @change="watchQuizTab">
          <el-radio-button value="practice">做题</el-radio-button>
          <el-radio-button value="wrong">错题本 ({{ wrongList.length || '' }})</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 做题 Tab -->
      <div v-if="quizTab === 'practice'" class="quiz-inner">
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

      <!-- 错题本 Tab -->
      <div v-if="quizTab === 'wrong'" class="quiz-inner">
        <div v-loading="wrongLoading" class="wrong-list">
          <el-empty v-if="wrongList.length === 0 && !wrongLoading" description="还没有错题——做题后这里会自动收集你做错的题" />
          <el-card v-for="(w, i) in wrongList" :key="i" class="wrong-card" shadow="never">
            <div class="wrong-q">
              <span class="wrong-score">{{ w.score }}分</span>
              {{ w.stem }}
            </div>
            <div v-if="w.type === 'single'" class="wrong-opts">
              <div v-for="opt in parseOptions(w.options)" :key="opt" :class="{ right: opt.charAt(0) === w.answer }">
                {{ opt }} {{ opt.charAt(0) === w.answer ? '✓' : '' }}
              </div>
              <div class="your-answer">你的答案：{{ w.user_answer?.replace(/"/g, '') || '（未作答）' }}</div>
            </div>
            <div v-else class="wrong-ans">
              <div class="label">你的回答：</div>
              <p>{{ w.user_answer?.replace(/"/g, '') || '（未作答）' }}</p>
            </div>
            <div class="wrong-exp">{{ w.explanation }}</div>
            <div class="wrong-actions">
              <el-button size="small" type="success" plain @click="deleteWrong(w.attempt_id, i)">
                ✓ 已掌握
              </el-button>
            </div>
          </el-card>
        </div>
      </div>
    </div>

    <!-- 查模式：段落卡片列表 -->
    <div v-else-if="mode === 'locate'" class="locate-wrap">
      <div ref="listEl" class="locate-list">
        <el-empty v-if="locateResults.length === 0 && !locating" description="丢一段话、一个术语、甚至半句记不全的话——直接定位到原文" />
        <el-card
          v-for="item in locateResults"
          :key="item.chunkId"
          class="locate-card clickable"
          shadow="hover"
          @click="openSource(item.chunkId)"
        >
          <div class="locate-src">
            {{ item.file }}<template v-if="item.page"> · 第{{ item.page }}页</template>
            <template v-if="item.section"> · {{ shortSection(item.section) }}</template>
            <span v-if="item.score" class="locate-score">相关度 {{ item.score }}</span>
            <span v-if="item.documentId && item.page" class="locate-jump">📄 看原文</span>
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

    <!-- 问模式：对话 + 侧边栏 -->
    <template v-else>
    <div class="chat-with-sidebar">
      <!-- 对话列表侧边栏 -->
      <div v-if="showConversationList" class="conv-sidebar">
        <el-button type="primary" size="small" style="width: 100%; margin-bottom: 10px" @click="newConversation">
          + 新对话
        </el-button>
        <div class="conv-list">
          <div
            v-for="conv in conversations"
            :key="conv.id"
            class="conv-item"
            :class="{ active: conv.id === currentConversationId }"
            @click="switchConversation(conv.id)"
          >
            <span class="conv-title">{{ conv.title || '新对话' }}</span>
            <span class="conv-meta">{{ conv.msg_count }} 条</span>
            <el-button
              text size="small" type="danger"
              class="conv-delete"
              @click.stop="deleteConversation(conv.id)"
            >×</el-button>
          </div>
        </div>
      </div>

    <div ref="listEl" class="msg-list" @click="onCitationClick">
      <div v-if="messages.length === 0" class="welcome">
        <div class="welcome-title">问点什么吧 📖</div>
        <div class="welcome-sub">答案只来自你上传的资料，每句话都标出处；资料里没有的会直说"没找到依据"</div>
        <div class="mode-switch">
          <el-radio-group v-model="chatMode" size="small">
            <el-radio-button value="strict">严格模式 · 只答资料内容</el-radio-button>
            <el-radio-button value="learn">学习模式 · 从资料延伸讲解</el-radio-button>
          </el-radio-group>
        </div>
        <div v-if="suggestLoading" class="chips"><span class="chip loading">正在根据你的资料想几个问题…</span></div>
        <div v-else-if="suggestions.length" class="chips">
          <span v-for="(q, i) in suggestions" :key="i" class="chip" :class="'c' + ((i % 4) + 1)" @click="fillExample(q)">
            {{ q }}
          </span>
        </div>
        <div class="plan-area">
          <el-button size="small" type="warning" plain :loading="planLoading" @click="generateStudyPlan">
            📋 生成学习计划
          </el-button>
        </div>
      </div>
      <!-- 学习计划展示 -->
      <div v-if="studyPlan" class="study-plan">
        <el-card shadow="never">
          <template #header>
            <span class="dot" style="background: var(--ws-orange)" />
            学习计划
            <el-button text size="small" style="float:right" @click="studyPlan = null">收起</el-button>
          </template>
          <template v-if="typeof studyPlan === 'object' && studyPlan !== null && studyPlan.overview">
            <p class="plan-overview">{{ studyPlan.overview }}</p>
            <div v-for="(ch, i) in studyPlan.chapters" :key="i" class="plan-chapter">
              <span class="plan-priority" :class="'p-' + ch.priority">{{ ch.priority }}</span>
              <b>{{ ch.title }}</b>
              <span class="plan-focus">{{ ch.focus }}</span>
              <span class="plan-time">约 {{ ch.estimated_minutes }} 分钟</span>
            </div>
            <div v-if="studyPlan.tips?.length" class="plan-tips">
              <div v-for="(tip, i) in studyPlan.tips" :key="i">💡 {{ tip }}</div>
            </div>
          </template>
          <pre v-else class="plan-raw">{{ studyPlan }}</pre>
        </el-card>
      </div>

      <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
        <div class="bubble">
          <template v-if="m.role === 'user'">{{ m.text }}</template>
          <template v-else>
            <div class="answer" v-html="renderAnswer(m.text) || (streaming && i === messages.length - 1 ? '思考中…' : '')" />
            <div v-if="m.citations.length > 0" class="citations">
              <div class="cite-title">答案依据（点击角标或这里，跳到 PDF 原文位置）</div>
              <div
                v-for="c in m.citations"
                :key="c.n"
                class="cite-item"
                @click="c.chunkId ? openSource(c.chunkId) : (dialogSnippet = c)"
              >
                <span class="cite-n">[{{ c.n }}]</span>
                <span class="cite-src">
                  {{ c.file }}<template v-if="c.page"> · 第{{ c.page }}页</template>
                  <template v-if="c.section"> · {{ shortSection(c.section) }}</template>
                </span>
              </div>
            </div>
            <div v-if="m.done && m.meta?.latencyMs" class="meta">
              {{ m.meta.latencyMs }}ms<template v-if="m.meta.completionTokens"> · {{ m.meta.completionTokens }} tokens</template>
              <template v-if="m.meta.error"> · {{ m.meta.error }}</template>
              <template v-if="devMode && m.meta.queryId && m.meta.queryId > 0">
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
    </div>
    </template>

    <el-dialog v-model="dialogSnippet" :title="dialogSnippet ? `[${dialogSnippet.n}] 原文片段` : ''" width="560px">
      <template v-if="dialogSnippet">
        <div class="dialog-src">
          {{ dialogSnippet.file }}
          <template v-if="dialogSnippet.page"> · 第 {{ dialogSnippet.page }} 页</template>
          <template v-if="dialogSnippet.section"> · {{ shortSection(dialogSnippet.section) }}</template>
        </div>
        <div class="dialog-content">{{ dialogSnippet.snippet }}</div>
      </template>
    </el-dialog>

    <SourceViewDialog v-model:visible="sourceView" :chunk-id="sourceChunkId" />
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
.chat-with-sidebar {
  flex: 1;
  display: flex;
  min-height: 0;
}
.conv-sidebar {
  width: 200px;
  border-right: 1px solid var(--ws-border);
  padding: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.conv-list {
  flex: 1;
  overflow-y: auto;
}
.conv-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 8px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 2px;
  font-size: 12px;
}
.conv-item:hover {
  background: var(--ws-blue-soft);
}
.conv-item.active {
  background: var(--ws-blue-soft);
  border-left: 3px solid var(--ws-blue);
}
.conv-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-meta {
  color: var(--ws-ink-light);
  font-size: 10px;
  white-space: nowrap;
}
.conv-delete {
  opacity: 0;
  padding: 0 2px;
}
.conv-item:hover .conv-delete {
  opacity: 1;
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
  margin-bottom: 14px;
}
.mode-switch {
  margin-bottom: 14px;
}
.plan-area {
  margin-top: 16px;
}
.study-plan {
  padding: 0 14px 10px;
}
.plan-overview {
  font-size: 14px;
  line-height: 1.8;
  color: var(--ws-ink);
  margin-bottom: 12px;
}
.plan-chapter {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--ws-border);
  font-size: 13px;
}
.plan-priority {
  display: inline-block;
  width: 28px;
  text-align: center;
  border-radius: 4px;
  font-size: 11px;
  padding: 1px 0;
}
.plan-priority.p-高 { background: #fce4ec; color: #c62828; }
.plan-priority.p-中 { background: #fff3e0; color: #ef6c00; }
.plan-priority.p-低 { background: #e8f5e9; color: #2e7d32; }
.plan-focus {
  color: var(--ws-ink-light);
  flex: 1;
}
.plan-time {
  color: var(--ws-ink-light);
  font-size: 12px;
  white-space: nowrap;
}
.plan-tips {
  margin-top: 12px;
  font-size: 13px;
  line-height: 2;
  color: var(--ws-ink);
}
.plan-raw {
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
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
.chip.c2 {
  background: var(--ws-green-soft);
  color: var(--ws-green);
}
.chip.c3 {
  background: var(--ws-orange-soft);
  color: var(--ws-orange);
}
.chip.c4 {
  background: var(--ws-purple-soft);
  color: var(--ws-purple);
}
.chip.loading {
  cursor: default;
  color: var(--ws-ink-light);
  background: #f3f0e9;
}
.quiz-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.quiz-tabs {
  padding: 0 0 10px;
}
.quiz-inner {
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
.wrong-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 14px 14px;
}
.wrong-card {
  margin-bottom: 10px;
  border-left: 3px solid #e67e7e;
}
.wrong-q {
  font-weight: 600;
  line-height: 1.6;
  margin-bottom: 8px;
}
.wrong-score {
  display: inline-block;
  background: #fceaea;
  color: #c74b4b;
  border-radius: 4px;
  padding: 1px 8px;
  font-size: 12px;
  margin-right: 6px;
  font-weight: 400;
}
.wrong-opts > div {
  padding: 3px 0;
  font-size: 13px;
}
.wrong-opts .right {
  color: var(--ws-green);
  font-weight: 600;
}
.your-answer {
  margin-top: 6px;
  font-size: 12px;
  color: #c74b4b;
}
.wrong-ans .label {
  font-size: 12px;
  color: var(--ws-ink-light);
  margin-bottom: 4px;
}
.wrong-ans p {
  font-size: 13px;
  line-height: 1.7;
  margin: 0 0 6px;
  color: #7a756a;
}
.wrong-exp {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ws-ink);
  background: var(--ws-bg);
  border-radius: 6px;
  padding: 8px 10px;
}
.wrong-actions {
  margin-top: 8px;
  text-align: right;
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
.locate-card.clickable {
  cursor: pointer;
}
.locate-card.clickable:hover {
  border-color: var(--ws-green);
}
.locate-jump {
  float: right;
  color: var(--ws-green);
  font-size: 12px;
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
