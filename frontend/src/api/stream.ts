import { getAuthorization } from './client'

/** Notifications trigger GET; their payloads never replace authoritative database state. */
export function stream(params: Record<string, string>, notify: () => void, state: (value: string) => void) {
  let stopped = false
  let controller: AbortController | undefined
  let lastId = ''
  let scheduled: ReturnType<typeof setTimeout> | undefined
  const refresh = () => { if (scheduled) clearTimeout(scheduled); scheduled = setTimeout(notify, 150) }
  const stop = () => { stopped = true; controller?.abort(); if (scheduled) clearTimeout(scheduled); window.removeEventListener('cae-logout', stop) }
  window.addEventListener('cae-logout', stop)
  const run = async () => {
    let failures = 0
    while (!stopped) {
      controller = new AbortController()
      try {
        state('连接中')
        const headers: Record<string, string> = {Authorization: getAuthorization(), Accept: 'text/event-stream'}
        if (lastId) headers['Last-Event-ID'] = lastId
        const response = await fetch('/api/v1/events/stream?' + new URLSearchParams(params), {headers, signal: controller.signal})
        if (response.status === 401 || response.status === 403) { state('无访问权限'); if (response.status === 401) window.dispatchEvent(new Event('cae-session-expired')); stop(); return }
        if (!response.ok || !response.body) throw new Error('Event stream unavailable')
        state('实时连接'); failures = 0; refresh()
        const reader = response.body.getReader(); const decoder = new TextDecoder(); let pending = ''; let event = ''; let data = ''
        while (!stopped) {
          const result = await reader.read(); if (result.done) break
          pending += decoder.decode(result.value, {stream: true})
          let newline: number
          while ((newline = pending.indexOf('\n')) >= 0) {
            const line = pending.slice(0, newline).replace(/\r$/, ''); pending = pending.slice(newline + 1)
            if (!line) { if (data && (event === 'ready' || event === 'job')) refresh(); event = ''; data = ''; continue }
            if (line.startsWith('id:')) lastId = line.slice(3).trim()
            if (line.startsWith('event:')) event = line.slice(6).trim()
            if (line.startsWith('data:')) data += line.slice(5).trimStart() + '\n'
          }
        }
      } catch (error) { if (stopped || (error instanceof DOMException && error.name === 'AbortError')) return }
      if (!stopped) { state('重连中 · 轮询兜底'); await new Promise(resolve => setTimeout(resolve, Math.min(15_000, 1000 * 2 ** Math.min(failures++, 4)) + Math.random() * 300)) }
    }
  }
  void run()
  return stop
}
