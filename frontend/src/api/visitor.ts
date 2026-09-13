/** 匿名访客身份：localStorage 里的 UUID，一个浏览器 = 一个访客（03 号文档 §5.4） */
const KEY = 'wenshu_visitor'

function generateId(): string {
  // crypto.randomUUID 仅在 HTTPS/localhost 下可用，HTTP 环境用降级方案
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

export function getVisitorId(): string {
  let v = localStorage.getItem(KEY)
  if (!v) {
    v = generateId()
    localStorage.setItem(KEY, v)
  }
  return v
}

/** 开发者模式：默认关闭——小白用户看不到调试台/分片预览等工程功能，页脚入口切换 */
const DEV_KEY = 'wenshu_dev'

export function isDevMode(): boolean {
  return localStorage.getItem(DEV_KEY) === '1'
}

export function toggleDevMode(): boolean {
  const next = !isDevMode()
  localStorage.setItem(DEV_KEY, next ? '1' : '0')
  return next
}
