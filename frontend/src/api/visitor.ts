/** 匿名访客身份：localStorage 里的 UUID，一个浏览器 = 一个访客（03 号文档 §5.4） */
const KEY = 'wenshu_visitor'

export function getVisitorId(): string {
  let v = localStorage.getItem(KEY)
  if (!v) {
    v = crypto.randomUUID()
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
