import axios from 'axios'
import { getVisitorId } from './visitor'
import type { DocStatus, KbInfo } from './types'

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

export interface ChunkPreviewResponse {
  strategy: string
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
