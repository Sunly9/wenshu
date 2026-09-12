export interface KbInfo {
  id: number
  name: string
  description: string | null
  shareCode: string
  owner: boolean
  documentCount: number
  createdAt: string
}

export interface Citation {
  n: number
  chunkId: number
  file: string
  page: number | null
  section: string | null
  snippet: string
}

export interface DocStatus {
  documentId: number
  fileName: string
  status: 'PENDING' | 'PARSING' | 'INDEXING' | 'READY' | 'FAILED'
  percent: number
  errorMsg: string | null
}

export interface DoneMeta {
  queryId?: number
  latencyMs?: number
  promptTokens?: number
  completionTokens?: number
  error?: string
}
