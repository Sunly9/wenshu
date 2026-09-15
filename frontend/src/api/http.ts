import axios from 'axios'
import { getVisitorId } from './visitor'
import type { DocStatus, KbInfo } from './types'

export type { DocStatus, KbInfo }

export const http = axios.create({ baseURL: '/api' })

http.interceptors.request.use((config) => {
  config.headers['X-Visitor-Id'] = getVisitorId()
  return config
})

export function errMsg(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const data = e.response?.data as { message?: string } | undefined
    return data?.message ?? e.message
  }
  return String(e)
}

export const kbApi = {
  list: () => http.get<KbInfo[]>('/kb').then((r) => r.data),
  create: (name: string, description: string) =>
    http.post<KbInfo>('/kb', { name, description }).then((r) => r.data),
  join: (code: string) => http.post<KbInfo>('/kb/join', { code }).then((r) => r.data),
  resetCode: (id: number) => http.post<KbInfo>(`/kb/${id}/code/reset`).then((r) => r.data),
  documents: (id: number) => http.get<DocStatus[]>(`/kb/${id}/documents`).then((r) => r.data),
  upload: (id: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http
      .post<{ documentId: number; status: string }>(`/kb/${id}/documents`, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data)
  },
  docStatus: (docId: number) => http.get<DocStatus>(`/documents/${docId}/status`).then((r) => r.data),
  previewChunks: (id: number, strategy: string, file: File) => {
    const form = new FormData()
    form.append('strategy', strategy)
    form.append('file', file)
    return http
      .post<ChunkPreviewResponse>(`/kb/${id}/chunks/preview`, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data)
  },
}

export interface ChunkPreviewResponse {  strategy: string
  pageCount: number
  parentCount: number
  childCount: number
  totalChildTokens: number
  truncated: boolean
  blocks: PreviewBlock[]
}

export interface PreviewBlock {
  kind: 'parent' | 'child'
  index: number
  parentIndex: number | null
  tokenCount: number
  sectionPath: string | null
  pageNo: number | null
  table: boolean
  content: string
}

// ---------- 检索调试台 ----------
export interface DebugCandidate {
  chunkId: number
  file: string
  section: string | null
  page: number | null
  snippet: string
  tokenCount: number
  vectorScore?: number
  ftsScore?: number
  rrfScore: number
  rerankScore?: number
  vectorRank?: number
  ftsRank?: number
}

export interface DebugMeta {
  vectorCount: number
  ftsCount: number
  fusedCount: number
  rerankCount: number
  vectorMs: number
  ftsMs: number
  fuseMs: number
  rerankMs: number
}

export interface DebugResp {
  queryId: number
  kbId: number
  question: string
  answer: string | null
  latencyMs: number
  retrieved: DebugCandidate[]
  chosenIds: number[]
  meta: DebugMeta | null
  createdAt: string
}

export const debugApi = {
  query: (kbId: number, question: string, strategy?: string) =>
    http.post<DebugResp>(`/kb/${kbId}/debug-query`, { question, strategy }).then((r) => r.data),
  byId: (queryId: number) => http.get<DebugResp>(`/debug/${queryId}`).then((r) => r.data),
  recent: (kbId: number) =>
    http
      .get<{ id: number; question: string; latency_ms: number; created_at: string }[]>(
        `/kb/${kbId}/debug/recent`,
        { params: { limit: 15 } },
      )
      .then((r) => r.data),
}

// ---------- 查模式（原文定位） ----------
export interface LocateItem {
  chunkId: number
  documentId?: number
  file: string
  section: string | null
  page: number | null
  content: string
  tokenCount: number
  score?: number
}

export const chatApi = {
  studyPlan: (kbId: number) =>
    http.post<string>(`/kb/${kbId}/study-plan`).then((r) => r.data),
}

// ---------- 对话管理 ----------
export interface Conversation {
  id: number
  title: string
  updated_at: string
  msg_count: number
}

export interface ConversationMessage {
  role: 'user' | 'assistant'
  content: string
  citations: string | null
}

export const conversationApi = {
  create: (kbId: number) =>
    http.post<{ id: number; title: string }>(`/kb/${kbId}/conversations`).then((r) => r.data),
  list: (kbId: number) =>
    http.get<Conversation[]>(`/kb/${kbId}/conversations`).then((r) => r.data),
  messages: (conversationId: number) =>
    http.get<ConversationMessage[]>(`/conversations/${conversationId}/messages`).then((r) => r.data),
  delete: (conversationId: number) =>
    http.delete<{ deleted: boolean }>(`/conversations/${conversationId}`).then((r) => r.data),
}

// ---------- 错题删除 ----------
export const wrongApi = {
  delete: (kbId: number, attemptId: number, questionIndex: number) =>
    http
      .post<{ deleted: boolean }>(`/kb/${kbId}/quiz/wrong/delete`, { attemptId, questionIndex })
      .then((r) => r.data),
}

export const locateApi = {
  query: (kbId: number, query: string) =>
    http.post<LocateItem[]>(`/kb/${kbId}/locate`, { query }).then((r) => r.data),
}

/** 章节路径只展示最内层（"第9章 > 9.3 哈希表" → "9.3 哈希表"），减少视觉噪音 */
export function shortSection(s: string | null | undefined): string {
  if (!s) return ''
  const parts = s.split(' > ').filter(Boolean)
  return parts.length > 0 ? parts[parts.length - 1] : s
}

// ---------- 示例问题（按库内容动态生成） ----------
export const suggestApi = {
  questions: (kbId: number) =>
    http.post<{ questions: string[] }>(`/kb/${kbId}/suggest-questions`).then((r) => r.data.questions),
}

// ---------- 练模式（出题判分） ----------
export interface QuizQuestion {
  type: 'single' | 'short'
  stem: string
  options: string[]
  answer: string
  explanation: string
  sourceChunkIds: number[]
}

export interface GradeItem {
  index: number
  type: 'single' | 'short'
  correct: boolean | null
  score: number
  comment: string
  missedSentences: string[]
}

export const quizApi = {
  generate: (kbId: number, documentId?: string, sectionPrefix?: string) =>
    http
      .post<QuizQuestion[]>(`/kb/${kbId}/quiz/generate`, { documentId, sectionPrefix })
      .then((r) => r.data),
  grade: (kbId: number, questions: QuizQuestion[], userAnswers: string[]) =>
    http.post<GradeItem[]>(`/kb/${kbId}/quiz/grade`, { questions, userAnswers }).then((r) => r.data),
  saveAttempt: (kbId: number, questions: QuizQuestion[], answers: string[], grades: GradeItem[]) =>
    http
      .post<{ saved: boolean; totalScore: number }>(`/kb/${kbId}/quiz/attempts`, { questions, answers, grades })
      .then((r) => r.data),
  attempts: (kbId: number) =>
    http
      .get<{ id: number; total_score: number; created_at: string; question_count: number; wrong_count: number }[]>(
        `/kb/${kbId}/quiz/attempts`,
      )
      .then((r) => r.data),
  wrong: (kbId: number) =>
    http
      .get<
        {
          attempt_id: number
          created_at: string
          type: string
          stem: string
          options: string
          answer: string
          explanation: string
          score: string
          comment: string
          missed_sentences: string
          user_answer: string
        }[]
      >(`/kb/${kbId}/quiz/wrong`)
      .then((r) => r.data),
}

// ---------- 评测集标注（D17） ----------
export type EvalType = 'FACT' | 'MULTI_HOP' | 'TABLE' | 'NO_ANSWER'

export interface EvalStats {
  FACT: number
  MULTI_HOP: number
  TABLE: number
  NO_ANSWER: number
  total: number
  [key: string]: number
}

export interface EvalRow {
  id: number
  type: EvalType
  question: string
  gold_chunk_ids: number[] | null
  gold_answer: string
}

export const evalApi = {
  add: (kbId: number, q: { question: string; type: EvalType; goldChunkIds: number[]; goldAnswer: string }) =>
    http.post<EvalStats>(`/kb/${kbId}/eval/questions`, q).then((r) => r.data),
  list: (kbId: number) => http.get<EvalRow[]>(`/kb/${kbId}/eval/questions`).then((r) => r.data),
  stats: (kbId: number) => http.get<EvalStats>(`/kb/${kbId}/eval/stats`).then((r) => r.data),
  remove: (kbId: number, id: number) =>
    http.delete<EvalStats>(`/kb/${kbId}/eval/questions/${id}`).then((r) => r.data),
  run: (kbId: number) => http.post<{ started: boolean }>('/eval/run', { kbId }).then((r) => r.data),
  generationRun: (kbId: number) =>
    http.post<{ started: boolean }>(`/kb/${kbId}/eval/generation-run`).then((r) => r.data),
  runs: (kbId: number) =>
    http
      .get<
        {
          id: number
          config: string
          hit_rate_at5: number | null
          mrr: number | null
          citation_accuracy: number | null
          faithfulness: number | null
          detail: string
          created_at: string
        }[]
      >(`/kb/${kbId}/eval/runs`)
      .then((r) => r.data),
}
