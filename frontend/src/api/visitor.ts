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
