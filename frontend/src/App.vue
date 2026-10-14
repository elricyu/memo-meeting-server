<script setup lang="ts">
import SummaryMarkdown from './SummaryMarkdown.vue'
import { meetingProgress } from './meeting-progress.js'
import { detailNeedsLive, defaultDetailTab } from './detail-state.js'
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { AudioLines, Plus, Search, Mic, Square, ArrowUpRight, FileText, Headphones, Copy, Download, RefreshCw, X, Check, Radio, Settings2, ChevronRight } from 'lucide-vue-next'
import { request, action, recorderToken, statusNames, time, type Config, type Detail, type Meeting, type ListedMeeting } from './api'
import { Recorder } from './recorder'
import AuthPanel from './AuthPanel.vue'
import DevicePanel from './DevicePanel.vue'
import AiSettings from './AiSettings.vue'
import type { User } from './api'
const currentUser = ref<User>(), authChecking = ref(true), deviceModal = ref(false), aiModal = ref(false), editingSpeaker = ref(''), speakerName = ref('')
const config = ref<Config>()
const records = ref<ListedMeeting[]>([])
const detail = ref<Detail>()
const search = ref(''), filter = ref('all'), tab = ref('transcript')
const modal = ref(false), title = ref(''), context = ref(''), error = ref(''), toast = ref('')
const loading = ref(false), creating = ref(false), starting = ref(false), recordingId = ref(''), stopping = ref(false), level = ref(0), submitting = ref(false)
const viewerConnected = ref(false), autoScroll = ref(true)
const transcript = ref<HTMLElement>(), player = ref<HTMLAudioElement>()
let pendingCreate: { clientRequestId: string; recorderToken: string; title: string; context: string } | undefined
let listEvents: EventSource | undefined, detailEvents: EventSource | undefined, recorder: Recorder | undefined
let selection = 0, refresh = 0, notifyTimer: ReturnType<typeof setTimeout> | undefined
const detailProgress = computed(() => detail.value ? meetingProgress(detail.value.meeting, detail.value.segments.filter(s => s.text.trim()).length) : [])
const viewingLive = computed(() => detailNeedsLive(detail.value))
const visible = computed(() => records.value.filter(m => m.title.toLowerCase().includes(search.value.toLowerCase()) && (filter.value === 'all' || (filter.value === 'live' ? ['RECORDING','CONNECTING','FINALIZING'].includes(m.status) : m.status === 'COMPLETED'))))
const liveCount = computed(() => records.value.filter(m => ['RECORDING','CONNECTING','FINALIZING'].includes(m.status)).length)
const totalDuration = computed(() => records.value.reduce((sum,m) => sum + m.audioBytes/32, 0))
const own = computed(() => detail.value && !!currentUser.value)
const canStart = computed(() => own.value && ['WAITING','INTERRUPTED','FAILED'].includes(detail.value!.meeting.status) && !recordingId.value && !starting.value && detail.value!.meeting.summaryStatus !== 'RUNNING')
const settled = computed(() => detail.value && ['COMPLETED','INTERRUPTED','FAILED'].includes(detail.value.meeting.status))
const speakerNames = computed(() => Object.fromEntries((detail.value?.speakers || []).map(s => [s.id, s.name])))
const duration = computed(() => time((detail.value?.meeting.audioBytes || 0)/32))
function home() { detailEvents?.close(); detail.value = undefined; history.replaceState(null, '', '/') }
function notify(message: string) { toast.value = message; clearTimeout(notifyTimer); notifyTimer = setTimeout(() => toast.value = '', 3200) }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
async function loadList() {
  const ticket = ++refresh
  try { const list = await request<ListedMeeting[]>('/meetings'); if (ticket === refresh) records.value = list }
  catch(e) { error.value = (e as Error).message }
}
async function select(id: string) {
  editingSpeaker.value = '';
  const ticket = ++selection
  detailEvents?.close(); detailEvents = undefined; viewerConnected.value = false; detail.value = undefined; loading.value = true
  history.replaceState(null, '', `?meeting=${id}`)
  try {
    const result = await request<Detail>(`/meetings/${id}`)
    if (ticket !== selection) return
    detail.value = result; tab.value = defaultDetailTab(result); autoScroll.value = detailNeedsLive(result)
    connectDetail(id, ticket)
  } catch(e) { if (ticket === selection) error.value = (e as Error).message }
  finally { if (ticket === selection) loading.value = false }
}
function connectDetail(id: string, ticket: number) {
  if (!detailNeedsLive(detail.value)) { detailEvents?.close(); detailEvents = undefined; viewerConnected.value = false; return }
  if (detailEvents) return
  const source = detailEvents = new EventSource(`/api/v1/meetings/${id}/events`)
  source.onopen = () => { if (ticket === selection && source === detailEvents) viewerConnected.value = true }
  source.onerror = () => { if (ticket === selection && source === detailEvents) viewerConnected.value = false }
  source.addEventListener('progress', event => {
    if (ticket !== selection || !detail.value) return
    detail.value.meeting.audioBytes = JSON.parse((event as MessageEvent).data).audioBytes
  })
  source.addEventListener('snapshot', async event => {
    if (ticket !== selection || source !== detailEvents) return
    const previous = detail.value
    detail.value = JSON.parse((event as MessageEvent).data)
    if (previous?.meeting.status !== 'COMPLETED' && detail.value?.meeting.status === 'COMPLETED' && ['RUNNING','DONE'].includes(detail.value.meeting.summaryStatus)) tab.value = 'summary'
    if (!detailNeedsLive(detail.value)) { source.close(); detailEvents = undefined; viewerConnected.value = false }
    if (autoScroll.value) { await nextTick(); transcript.value?.scrollTo({ top: transcript.value.scrollHeight, behavior: 'smooth' }) }
  })
}
async function refreshSelected(id: string) {
  const ticket = selection
  const result = await request<Detail>(`/meetings/${id}`)
  if (ticket !== selection || detail.value?.meeting.id !== id) return
  const previous = detail.value
  detail.value = result
  if (result.meeting.status === 'COMPLETED' && ['RUNNING','DONE'].includes(result.meeting.summaryStatus) && (previous?.meeting.status !== 'COMPLETED' || previous?.meeting.summaryStatus === 'IDLE')) tab.value = 'summary'
  connectDetail(id, ticket)
}
async function create() {
  if (creating.value || !title.value.trim()) return
  creating.value = true
  try {
    if (!pendingCreate || pendingCreate.title !== title.value.trim() || pendingCreate.context !== context.value.trim())
      pendingCreate = { clientRequestId: crypto.randomUUID(), recorderToken: Array.from(crypto.getRandomValues(new Uint8Array(32)), n => n.toString(16).padStart(2,'0')).join(''), title: title.value.trim(), context: context.value.trim() }
    const result = await request<{meeting: Meeting; recorderToken: string}>('/meetings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...pendingCreate, source: 'WEB' }) })
    localStorage.setItem(`memo-recorder-${result.meeting.id}`, result.recorderToken)
    modal.value = false; pendingCreate = undefined; title.value = ''; context.value = ''; await loadList(); await select(result.meeting.id)
  } catch(e) { error.value = (e as Error).message }
  finally { creating.value = false }
}
async function startRecording() {
  if (!detail.value || !canStart.value) return
  const id = detail.value.meeting.id
  starting.value = true; error.value = ''
  const instance = recorder = new Recorder(message => error.value = message, () => { recordingId.value = ''; stopping.value = false; recorder = undefined; void loadList() }, value => level.value = value)
  try { await instance.start(id, recorderToken(id)); recordingId.value = id; notify('正在录音，音频自动保存；需要文字时请点击实时转录') }
  catch(e) { error.value = (e as Error).message }
  finally { starting.value = false; void loadList() }
}
async function stopRecording() { stopping.value = true; await recorder?.stop() }
async function run(name: string) {
  if (!detail.value || submitting.value) return
  const id = detail.value.meeting.id
  submitting.value = true
  try { await action(id, name); notify(name === 'summary' ? '正在生成纪要' : name === 'retranscribe' ? '已开始转录，完成前保留原文' : name === 'local-transcription' ? '本地转录已排队' : name === 'transcription/start' ? '实时转录已开启，将识别接下来的发言' : '记录已结束'); await loadList(); await refreshSelected(id) }
  catch(e) { error.value = (e as Error).message }
  finally { submitting.value = false }
}
async function share() {
  try { await navigator.clipboard.writeText(`${location.origin}/?meeting=${detail.value!.meeting.id}`); notify('链接已复制，同一账号在另一台设备登录后即可查看') }
  catch { error.value = '复制失败，请复制浏览器地址栏中的链接' }
}
async function copyText() {
  try { await navigator.clipboard.writeText(tab.value === 'summary' ? detail.value!.meeting.summary : detail.value!.segments.map(s => `[${time(s.startMs)}] ${s.text}`).join('\n')); notify('文字已复制') }
  catch { error.value = '复制失败，请手动选中文字' }
}
function seek(ms: number) { if (player.value) { player.value.currentTime = ms/1000; void player.value.play().catch(() => {}) } }
function trackScroll() { const el = transcript.value; if (el) autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100 }
function unload(e: BeforeUnloadEvent) { if (recordingId.value || starting.value) { e.preventDefault(); e.returnValue = '' } }
async function refreshConfig() { try { config.value = await request<Config>('/config') } catch(e) { error.value = (e as Error).message } }
async function renameSpeaker() {
  if (!detail.value || !editingSpeaker.value) return
  submitting.value = true
  const id = detail.value.meeting.id, speaker = editingSpeaker.value
  try { const updated = await request<Detail>(`/meetings/${id}/speakers/${encodeURIComponent(speaker)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: speakerName.value.trim() }) }); if (detail.value?.meeting.id === id) detail.value = updated; editingSpeaker.value = ''; notify('姓名已更新，已有原文和后续发言立即生效') }
  catch(e) { error.value = (e as Error).message }
  finally { submitting.value = false }
}
async function initialize(user: User) {
  currentUser.value = user; error.value = ''
  await refreshConfig()
  await loadList()
  listEvents?.close()
  listEvents = new EventSource('/api/v1/events'); listEvents.addEventListener('changed', async event => { void loadList(); const changed = JSON.parse((event as MessageEvent).data); if (detail.value?.meeting.id === changed.id && !detailEvents) { try { await refreshSelected(changed.id) } catch(e) { error.value = (e as Error).message } } }); listEvents.addEventListener('configuration', () => void refreshConfig()); listEvents.onopen = () => void loadList()
  const id = new URLSearchParams(location.search).get('meeting'); if (id) await select(id)
}
function unauthorized() {
  listEvents?.close(); detailEvents?.close(); void recorder?.cleanup()
  currentUser.value = undefined; detail.value = undefined; records.value = []; deviceModal.value = false; aiModal.value = false
}
async function logout() {
  try { await request('/auth/logout', { method: 'POST' }); unauthorized(); home() }
  catch(e) { error.value = (e as Error).message }
}
onMounted(async () => {
  window.addEventListener('beforeunload', unload)
  window.addEventListener('memo-unauthorized', unauthorized)
  try { const result = await request<{ user: User | null }>('/auth/me'); if (result.user) await initialize(result.user) }
  catch(e) { error.value = (e as Error).message }
  finally { authChecking.value = false }
})
onUnmounted(() => { listEvents?.close(); detailEvents?.close(); void recorder?.cleanup(); clearTimeout(notifyTimer); window.removeEventListener('beforeunload', unload); window.removeEventListener('memo-unauthorized', unauthorized) })
</script>

<template>
  <div v-if="authChecking" class="auth-loading">正在打开你的工作空间…</div>
  <AuthPanel v-else-if="!currentUser" @authenticated="initialize" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="/" @click.prevent="home"><span class="brand-mark"><AudioLines :size="23" /></span><span>Memo<span class="brand-light"> Meeting</span></span></a>
      <span class="workspace-label">我的工作空间</span>
      <button class="nav-item selected" @click="filter = 'all'"><FileText :size="18" />会议记录<span class="count">{{ records.length }}</span></button>
      <button class="nav-item device-nav" @click="deviceModal = true"><Radio :size="18" />我的眼镜</button>
      <button v-if="currentUser.role === 'ADMIN'" class="nav-item device-nav" @click="aiModal = true"><Settings2 :size="18" />AI 服务设置</button>
      <div class="account-menu"><strong>{{ currentUser.displayName }}</strong><span>@{{ currentUser.username }}</span><button :disabled="!!recordingId || starting" @click="logout">退出登录</button></div>
      <div class="sidebar-note"><Radio :size="20" /><strong>让讨论留下来</strong><p>记录端专注录音，查看端同步文字。重要结论，随时回看。</p></div>
      <div class="sidebar-footer"><span class="connection-dot" :class="{ muted: !config?.asrReady }"></span><div>{{ config?.asrReady ? '云端识别已配置' : '云端识别待配置' }}<small>{{ config?.asrCapabilities?.label || config?.asrModel || '连接服务中…' }}</small></div></div>
    </aside>
    <main class="workspace">
      <header class="topbar"><span>工作空间 <ChevronRight :size="14" /> 会议记录</span><div class="mobile-account"><button v-if="currentUser.role === 'ADMIN'" @click="aiModal = true">AI 设置</button><span>{{ currentUser.displayName }}</span><button :disabled="!!recordingId || starting" @click="logout">退出</button></div><a class="topbar-caption" href="/docs/openapi.json" target="_blank" rel="noopener">接口文档 ↗</a></header>
      <div v-if="error" class="alert" role="alert"><span>{{ error }}</span><button aria-label="关闭提示" @click="error = ''"><X :size="17" /></button></div>
      <div v-if="recordingId" class="recording-banner"><span class="pulse-dot"></span><span>记录端正在{{ stopping ? '确认最终文字' : '录音' }}，请保持此页面打开</span><button @click="select(recordingId)">查看当前记录 <ArrowUpRight :size="14" /></button><button v-if="!stopping" @click="stopRecording">停止录音</button></div>
      <section class="page-heading"><div><div class="eyebrow">MEETING NOTES</div><h1>每一次讨论，都有迹可循。</h1><p>实时记录会议，让你专注于眼前的对话。</p></div><button class="primary" @click="modal = true"><Plus :size="18" />新建记录</button></section>
      <div v-if="config && !config.asrReady" class="config-note"><Settings2 :size="19" /><div><strong>配置云端识别，按需转录会议</strong><span>请联系管理员完成配置，普通用户无需填写 Key。</span></div><button v-if="currentUser.role === 'ADMIN'" class="secondary small" @click="aiModal = true">配置 AI</button></div>
      <section class="stats"><div><span>全部记录</span><strong>{{ records.length }}<small>场会议</small></strong></div><div><span>实时进行中</span><strong>{{ liveCount }}<small>场会议</small><i v-if="liveCount" class="pulse-dot"></i></strong></div><div><span>已保存的讨论</span><strong>{{ Math.floor(totalDuration/60000) }}<small>分钟</small></strong></div></section>
      <div class="content-grid" :class="{ 'with-detail': detail || loading }">
        <section class="records-panel">
          <div class="list-toolbar"><div class="filters"><button :class="{ active: filter === 'all' }" @click="filter = 'all'">全部</button><button :class="{ active: filter === 'live' }" @click="filter = 'live'">进行中</button><button :class="{ active: filter === 'done' }" @click="filter = 'done'">已完成</button></div><label class="search"><Search :size="16" /><input v-model="search" placeholder="搜索会议" aria-label="搜索会议" /></label></div>
          <div v-if="!visible.length" class="empty-list"><div class="empty-icon"><AudioLines :size="32" /></div><h2>{{ search || filter !== 'all' ? '没有找到会议' : '你的第一条会议记录，从这里开始' }}</h2><p>新建一条记录，开启麦克风。<br />也可以绑定眼镜，让会议自动出现在这里。</p><button v-if="!search && filter === 'all'" class="secondary" @click="modal = true"><Plus :size="16" />创建会议记录</button></div>
          <button v-for="meeting in visible" :key="meeting.id" class="meeting-card" :class="{ current: detail?.meeting.id === meeting.id }" @click="select(meeting.id)"><span class="meeting-icon" :class="{ live: meeting.status === 'RECORDING' }"><AudioLines v-if="meeting.status === 'RECORDING'" :size="21" /><FileText v-else :size="20" /></span><div class="meeting-info"><div class="meeting-title">{{ meeting.title }}</div><div class="meeting-meta">{{ date(meeting.createdAt) }}<span>·</span>{{ time(meeting.audioBytes/32) }}<span v-if="meeting.source === 'GLASSES'">· 眼镜录音</span></div><div v-if="meetingProgress(meeting).length" class="meeting-progress"><span v-for="label in meetingProgress(meeting)" :key="label.text" class="badge progress-badge" :class="label.tone">{{ label.text }}</span></div></div><span class="badge" :class="meeting.status.toLowerCase()"><i v-if="meeting.status === 'RECORDING'" class="pulse-dot"></i>{{ statusNames[meeting.status] }}</span><ChevronRight :size="16" class="card-chevron" /></button>
          <div class="list-footnote">音频自动保存 · 转录按需开启</div>
        </section>
        <section v-if="detail || loading" class="detail-panel">
          <div v-if="loading" class="detail-loading">正在打开记录…</div>
          <template v-else-if="detail">
            <div class="detail-header"><div><span class="eyebrow">{{ viewingLive ? '会议进行中 · 实时同步' : '已保存的会议记录' }}</span><h2>{{ detail.meeting.title }}</h2><span class="detail-meta">{{ date(detail.meeting.createdAt) }} <span>·</span> {{ duration }}</span><div v-if="detailProgress.length" class="meeting-progress"><span v-for="label in detailProgress" :key="label.text" class="badge progress-badge" :class="label.tone">{{ label.text }}</span></div></div><button class="icon-button" aria-label="复制查看链接" title="复制查看链接" @click="share"><ArrowUpRight :size="19" /></button></div>
            <div class="record-controls"><div class="record-state"><span class="connection-dot" :class="{ muted: viewingLive && !viewerConnected }"></span>{{ viewingLive ? (viewerConnected ? '实时同步已连接' : '正在连接实时更新…') : '已保存 · 无需实时连接' }}</div><button v-if="canStart || starting" class="primary small" :disabled="starting" @click="startRecording"><Mic :size="15" />{{ starting ? '启动录音中…' : detail.meeting.status === 'WAITING' ? '开始录音' : '继续录音' }}</button><button v-if="recordingId === detail.meeting.id" class="stop small" :disabled="stopping" @click="stopRecording"><Square :size="12" fill="currentColor" />{{ stopping ? '确认中…' : '停止录音' }}</button><span v-else-if="detail.meeting.status === 'RECORDING'" class="badge recording">录音中</span></div>
            <div v-if="recordingId === detail.meeting.id && !stopping" class="waveform"><span v-for="n in 36" :key="n" :style="{ height: `${5 + level * 30 * (0.3 + Math.abs(Math.sin(n * 1.4)))}px` }"></span><small>正在聆听</small></div>
            <p v-if="detail.meeting.error" class="inline-warning">{{ detail.meeting.error }}</p>
            <div class="detail-tabs"><button :class="{ active: tab === 'transcript' }" @click="tab = 'transcript'"><AudioLines :size="16" />转录原文<span>{{ detail.segments.length }}</span></button><button :class="{ active: tab === 'summary' }" @click="tab = 'summary'"><FileText :size="16" />AI 纪要<span v-if="detail.meeting.summaryStatus === 'DONE'">已生成</span><span v-else-if="detail.meeting.summaryStatus === 'RUNNING'">生成中</span></button><button class="icon-button" aria-label="复制当前文字" @click="copyText"><Copy :size="15" /></button></div>
            <p v-if="detail.localTranscription?.status" class="inline-warning">本地转录：{{ ({ QUEUED: '排队中', RUNNING: '处理中', RETRY: '等待重试', DONE: '已完成', FAILED: '处理失败', EMPTY: '未检出语音', SKIPPED: '已保留现有记录' } as Record<string,string>)[detail.localTranscription.status] || detail.localTranscription.status }}<span v-if="detail.localTranscription.error"> · {{ detail.localTranscription.error }}</span><button v-if="['FAILED','EMPTY','SKIPPED'].includes(detail.localTranscription.status) && !detail.segments.length" :disabled="submitting" @click="run('local-transcription')">重试本地转录</button></p>
            <p v-if="detail.transcriptionError" class="inline-warning">{{ detail.transcriptionError }}</p>
            <p v-if="detail.segments.length && !detail.speakers.length" class="inline-warning">{{ settled ? '这份记录尚无说话人标签。配置实时分人服务后，可用「转录」补齐。' : '当前识别尚未返回说话人标签。实时分人需要选择支持该能力的服务并启用。' }}<button v-if="currentUser.role === 'ADMIN'" @click="aiModal = true"> 配置实时分人</button></p>
            <div v-if="tab === 'transcript'" ref="transcript" class="transcript" @scroll="trackScroll">
              <div v-if="!detail.segments.length && !detail.draft" class="transcript-empty"><Mic :size="28" /><h3>{{ detail.meeting.status === 'RECORDING' ? detail.transcribing ? '正在聆听，请开始说话' : '正在录音，尚未开启转录' : '这里将出现你的转录文字' }}</h3><p>{{ detail.meeting.status === 'RECORDING' ? (detail.transcribing ? '文字随发言更新，音频继续保存。' : '点击实时转录后识别接下来的发言，音频继续保存。') : settled ? '点击转录，生成完整录音的文字。' : '开始录音后可按需开启实时转录。' }}</p></div>
              <form v-if="editingSpeaker" class="speaker-edit" @submit.prevent="renameSpeaker"><label>说话人名称<input v-model="speakerName" required maxlength="60" aria-label="说话人名称" /></label><button class="primary small" :disabled="submitting">保存名称</button><button type="button" :disabled="submitting" @click="editingSpeaker = ''">取消</button></form>
              <div v-for="segment in detail.segments" :key="segment.id" class="sentence"><button class="timestamp" :disabled="!settled" :title="settled ? '回听这句话' : '录音结束后可回听'" @click="seek(segment.startMs)">{{ time(segment.startMs) }}</button><div class="sentence-content"><button v-if="segment.speakerId" class="speaker-name" :disabled="submitting || detail.meeting.status === 'REPROCESSING' || detail.meeting.summaryStatus === 'RUNNING'" title="点击修改姓名，保存后立即更新本场会议" @click="editingSpeaker = segment.speakerId; speakerName = speakerNames[segment.speakerId] || ''">{{ speakerNames[segment.speakerId] || '未标记说话人' }}</button><p>{{ segment.text }}</p></div></div>
              <div v-if="viewingLive && detail.draft" class="sentence draft"><span class="timestamp">识别中</span><p>{{ detail.draft }}<span class="cursor"></span></p></div>
              <div v-if="detail.meeting.status === 'FINALIZING'" class="processing-note">正在确认最后一句，请稍候…</div>
              <div v-if="detail.meeting.status === 'REPROCESSING'" class="processing-note">正在用已保存的完整录音重新识别。完成后替换原文；失败会保留原文。</div>
            </div>
            <div v-else class="summary-pane">
              <div v-if="!detail.meeting.summary" class="summary-empty"><FileText :size="28" /><h3>{{ detail.meeting.summaryStatus === 'RUNNING' ? '正在自动整理会议纪要…' : '把讨论变成清晰的行动' }}</h3><p>会议正常结束后，自动生成摘要、结论与待办，并命名会议。<br />没有明确的负责人和日期会标为“未明确”。</p></div>
              <SummaryMarkdown v-else :text="detail.meeting.summary" />
              <p v-if="detail.meeting.summaryError" class="inline-warning">{{ detail.meeting.summaryError }}</p>
              <p v-if="config && !config.llmReady" class="muted-note">纪要服务尚未配置，请联系管理员</p>
              <p v-if="detail.meeting.summary" class="muted-note">AI 纪要依据转录生成，重要结论请对照原文和录音确认。</p>
              <p v-if="detail.meeting.summary && detail.meeting.summaryStatus === 'RUNNING'" class="muted-note" role="status">正在更新会议纪要…</p>
              <div v-if="own && settled && detail.meeting.summaryStatus !== 'RUNNING'" class="summary-actions"><button type="button" class="summary-regenerate" :disabled="!config?.llmReady || !detail.segments.length || submitting" title="根据转录重新生成会议纪要和标题" @click="run('summary')">{{ detail.meeting.summaryStatus === 'FAILED' ? '重试生成' : detail.meeting.summary ? '重新生成' : '生成纪要' }}</button></div>
            </div>
            <div v-if="settled && detail.meeting.audioBytes" class="audio-player"><div><Headphones :size="15" />录音回听<a :href="`/api/v1/meetings/${detail.meeting.id}/audio`" download><Download :size="15" /><span>保存音频</span></a></div><audio :key="detail.meeting.id + detail.meeting.audioBytes" ref="player" controls preload="metadata" :src="`/api/v1/meetings/${detail.meeting.id}/audio`"></audio></div>
            <footer class="detail-footer"><span v-if="tab === 'transcript'">{{ detail.meeting.status === 'RECORDING' && !detail.transcribing ? '音频自动保存，转录按需开启' : detail.draft ? '灰色文字仍在识别中' : detail.segments.length ? '已确认文字自动保存，可回听与导出' : '音频已保存，点击转录生成文字' }}</span><a :href="`/api/v1/meetings/${detail.meeting.id}/export`"><Download :size="14" />导出记录</a><button v-if="own && detail.meeting.status === 'RECORDING'" :disabled="submitting || !config?.asrReady || detail.transcribing || detail.transcriptionStarting" @click="run('transcription/start')"><AudioLines :size="14" />{{ detail.transcriptionStarting ? '正在开启…' : detail.transcribing ? '实时转录已开启' : '实时转录' }}</button><button v-if="own && settled && detail.meeting.audioBytes" :disabled="submitting || !config?.asrReady || detail.meeting.summaryStatus === 'RUNNING'" @click="run('retranscribe')"><RefreshCw :size="14" />转录</button><button v-if="own && ['WAITING','INTERRUPTED','FAILED'].includes(detail.meeting.status)" :disabled="submitting || starting" @click="run('finish')"><Check :size="14" />结束记录</button></footer>
          </template>
        </section>
      </div>
      <footer class="page-footer">MEMO MEETING <span>把注意力交给交流，把记录交给 Memo。</span></footer>
    </main>
    <div v-if="modal" class="modal-backdrop" @click.self="!creating && (modal = false)"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><button class="modal-close icon-button" aria-label="关闭" :disabled="creating" @click="modal = false"><X :size="19" /></button><span class="eyebrow">A NEW CONVERSATION</span><h2 id="modal-title">开始一场值得记录的讨论</h2><p>先创建记录，再开启麦克风。创建后即可分享查看链接。</p><div v-if="error" class="inline-warning" role="alert">{{ error }}</div><form @submit.prevent="create"><label>会议主题<input v-model="title" autofocus required maxlength="200" placeholder="例如：产品周会 / 项目方案讨论" /></label><label>参考术语 <span>可选</span><textarea v-model="context" maxlength="400" rows="3" placeholder="输入产品名、人名或专业词，帮助云端识别理解上下文。" /></label><div class="form-foot"><span>{{ context.length }}/400</span><button class="primary" :disabled="creating || !title.trim()"><Plus :size="17" />{{ creating ? '创建中…' : '创建记录' }}</button></div></form></section></div>
    <AiSettings v-if="aiModal" @close="aiModal = false" @saved="refreshConfig" />
    <DevicePanel v-if="deviceModal" :user-name="currentUser.displayName" @close="deviceModal = false" @notify="notify" />
    <div v-if="toast" class="toast" role="status"><Check :size="17" />{{ toast }}</div>
  </div>
</template>
