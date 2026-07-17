import http from './http'

// 登录：POST /api/auth/login {username,password} → Result<LoginVO>{token,username}
export function login(username, password) {
  return http.post('/auth/login', { username, password }).then((r) => r.data.data)
}

// 解析单个 SSE 文本块（Spring SseEmitter 输出：event:xxx\ndata:yyy\n）
function parseSseBlock(block) {
  let event = 'message'
  let data = ''
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  return { event, data }
}

/**
 * 流式对话：POST /api/agent/chat（SSE）。
 * @param {object} p
 * @param {string} p.query        用户问题
 * @param {string} p.sessionId    会话 ID（可选）
 * @param {string} p.token        Sa-Token token（用于请求头）
 * @param {Function} p.onEvent    每收到一个 SSE 事件回调 ({event,data})
 * @param {AbortSignal} p.signal  用于中断
 * @returns {Promise<void>} 流结束 resolve；网络错误 reject
 */
export async function streamChat({ query, sessionId, token, onEvent, signal }) {
  const resp = await fetch('/api/agent/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      satoken: token || '',
      Authorization: 'Bearer ' + (token || '')
    },
    body: JSON.stringify({ query, sessionId }),
    signal
  })
  if (!resp.ok) {
    let msg = 'HTTP ' + resp.status
    try {
      const j = await resp.json()
      if (j && j.msg) msg = j.msg
    } catch (e) {
      /* ignore */
    }
    throw new Error(msg)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      if (block.trim()) onEvent(parseSseBlock(block))
    }
  }
  if (buffer.trim()) onEvent(parseSseBlock(buffer))
}

// 会话历史列表：GET /api/agent/history/list → PageResult<SessionSummaryVo>
export function listHistory(page = 0, size = 20) {
  return http
    .get('/agent/history/list', { params: { page, size } })
    .then((r) => r.data.data)
}

// 会话详情：GET /api/agent/history/{sid} → SessionDetailVo{messages:[{role,content}]}
export function getHistory(sid) {
  return http.get('/agent/history/' + sid).then((r) => r.data.data)
}

// 删除单条会话：DELETE /api/agent/history/{sid}
export function deleteHistory(sid) {
  return http.delete('/agent/history/' + sid).then((r) => r.data)
}

// 清空当前用户全部会话：DELETE /api/agent/history/clear
export function clearHistory() {
  return http.delete('/agent/history/clear').then((r) => r.data)
}
