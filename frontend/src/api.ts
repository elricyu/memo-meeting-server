export type User = { id: number; username: string; displayName: string; role: 'ADMIN' | 'USER' }
let csrf = ''
async function csrfToken(signal: AbortSignal) {
  if (!csrf) {
    const response = await fetch('/api/v1/auth/csrf', { signal })
    if (!response.ok) throw new Error('无法初始化安全会话，请刷新页面')
    csrf = (await response.json()).token
  }
  return csrf
}
export type Meeting = { source: string; ownerId: number; deviceId: string | null; id: string; title: string; context: string; status: string; createdAt: string; updatedAt: string; audioBytes: number; error: string; summary: string; summaryStatus: string; summaryError: string }
export type ListedMeeting = Meeting & { transcriptCount: number }
export type Segment = { id: string; startMs: number; endMs: number; text: string; speakerId?: string | null }
export type Detail = { meeting: Meeting; segments: Segment[]; draft: string; speakers: { id: string; name: string }[]; transcribing: boolean; transcriptionStarting: boolean; transcriptionError: string }
export type Config = { asrReady: boolean; llmReady: boolean; asrModel: string; asrProvider: string; asrCapabilities?: { label: string; partialResults: boolean; speakerMode: string; note: string }; diarization: boolean; maxSeconds: number }
export async function request<T>(path: string, options?: RequestInit, timeoutMs = 15000): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const headers = new Headers(options?.headers)
    if (options?.method && !['GET','HEAD'].includes(options.method.toUpperCase())) headers.set('X-XSRF-TOKEN', await csrfToken(controller.signal))
    const response = await fetch(`/api/v1${path}`, { ...options, headers, signal: options?.signal || controller.signal })
    if (response.status === 401 && !path.startsWith('/auth/')) window.dispatchEvent(new Event('memo-unauthorized'))
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || `请求失败 (${response.status})`) }
    return await response.json()
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw new Error('请求超时，请检查网络后重试')
    if (error instanceof TypeError) throw new Error('无法连接会议服务，请检查后端和网络')
    throw error
  } finally { clearTimeout(timer) }
}
export function recorderToken(id: string) { return localStorage.getItem(`memo-recorder-${id}`) || '' }
export function action(id: string, name: string) { return request(`/meetings/${id}/${name}`, { method: 'POST', headers: { 'X-Recorder-Token': recorderToken(id) } }) }
export const statusNames: Record<string, string> = { WAITING: '待录音', CONNECTING: '连接中', RECORDING: '录音中', FINALIZING: '保存中', COMPLETED: '已完成', INTERRUPTED: '已中断', FAILED: '保存失败', REPROCESSING: '转录中' }
export function time(ms: number) { const s = Math.floor(ms / 1000); return `${Math.floor(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}` }
