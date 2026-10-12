import axios from 'axios'

let authorization = ''
export const setAuthorization = (value: string) => { authorization = value }
export const getAuthorization = () => authorization

export const api = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  headers: { Accept: 'application/json' },
})

api.interceptors.request.use(config => {
  if (authorization) config.headers.Authorization = authorization
  return config
})
api.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401) window.dispatchEvent(new Event('cae-session-expired'))
  return Promise.reject(error)
})

export function errorText(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as {message?: string; code?: string; traceId?: string} | undefined
    if (data) return `${data.message ?? '请求失败'}${data.code ? ` · ${data.code}` : ''}${data.traceId ? ` · Trace ${data.traceId}` : ''}`
    return error.code === 'ECONNABORTED' ? '请求超时，请使用原请求重试。' : '网络暂不可用，请重试。'
  }
  return error instanceof Error ? error.message : '操作失败，请重试。'
}

export async function download(url: string, name: string) {
  const response = await api.get(url.startsWith('/api/v1/') ? url.slice(7) : url, {responseType: 'blob'})
  const blob = URL.createObjectURL(response.data)
  try { const anchor = document.createElement('a'); anchor.href = blob; anchor.download = name; anchor.click() }
  finally { setTimeout(() => URL.revokeObjectURL(blob), 1000) }
}

export function time(value?: string | null) {
  return value ? new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium', hour12: false}).format(new Date(value)) : '—'
}
