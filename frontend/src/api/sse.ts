import { getVisitorId } from './visitor'
import type { Citation, DoneMeta } from './types'

export interface ChatHandlers {
  onCitation: (citations: Citation[]) => void
  onToken: (text: string) => void
  onDone: (meta: DoneMeta) => void
}

/** POST /api/chat 的 SSE 流解析（fetch + ReadableStream，因为 EventSource 只支持 GET） */
export async function chatStream(kbId: number, question: string, handlers: ChatHandlers,
                                mode?: string, history?: Array<{ role: string; content: string }>,
                                conversationId?: number): Promise<void> {
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Visitor-Id': getVisitorId() },
    body: JSON.stringify({
      kbId, question,
      mode: mode || 'strict',
      history: history || [],
      conversationId: conversationId || null,
    }),
  })
  if (!res.ok || !res.body) {
    let message = `请求失败（${res.status}）`
    try {
      const data = (await res.json()) as { message?: string }
      if (data.message) message = data.message
    } catch {
      /* 保留默认提示 */
    }
    handlers.onDone({ error: message })
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let sep: number
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      handleBlock(block, handlers)
    }
  }
}

function handleBlock(block: string, handlers: ChatHandlers): void {
  let event = 'message'
  let data = ''
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  if (!data) return
  const parsed = JSON.parse(data) as Record<string, unknown>
  if (event === 'citation') handlers.onCitation(parsed.citations as Citation[])
  else if (event === 'token') handlers.onToken(parsed.text as string)
  else if (event === 'done') handlers.onDone(parsed as DoneMeta)
}
