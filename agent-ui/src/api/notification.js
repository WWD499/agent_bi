import http from './http'

// 站内信 / 通知中心接口（基路径 /bi/notify）
const BASE = '/bi/notify'

// 列表（params: { unreadOnly?, page, size }）
export function listNotifications(params) {
  return http.get(`${BASE}/list`, { params }).then((r) => r.data.data)
}

// 未读数量
export function unreadCount() {
  return http.get(`${BASE}/unread-count`).then((r) => r.data.data)
}

// 单条详情
export function getNotification(id) {
  return http.get(`${BASE}/${id}`).then((r) => r.data.data)
}

// 标记单条已读
export function markRead(id) {
  return http.post(`${BASE}/${id}/read`).then((r) => r.data.data)
}

// 全部已读
export function markAllRead() {
  return http.post(`${BASE}/read-all`).then((r) => r.data.data)
}

// 删除单条
export function deleteNotification(id) {
  return http.delete(`${BASE}/${id}`).then((r) => r.data.data)
}

// 批量删除（ids: number[]）
export function deleteNotifications(ids) {
  return http.delete(`${BASE}/${ids.join(',')}`).then((r) => r.data.data)
}
