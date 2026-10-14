import { Session, native } from './session.mjs'
import assert from 'node:assert/strict'
import { randomUUID, randomBytes } from 'node:crypto'
import { encodeAudioFrame } from '../frontend/public/audio-frame.js'
import { once } from 'node:events'
import WebSocket from '../frontend/node_modules/ws/wrapper.mjs'
const base = process.env.TEST_URL || 'http://127.0.0.1:19090'
const wsBase = base.replace('http', 'ws')
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const session = new Session(base)
assert.equal((await native(base,'/meetings')).status,401)
await session.register('protocol')
async function api(path, method = 'GET', body, token) { return session.api(path,method,body,token?{'X-Recorder-Token':token}:{}) }

async function waitFor(fn, label) {
  for (let i = 0; i < 100; i++) { const result = await fn(); if (result) return result; await delay(100) }
  throw new Error(`Timed out: ${label}`)
}
async function create(title, context = '') { const response = await api('/meetings', 'POST', { title, context }); assert.equal(response.status, 200); return response.body }
async function recorder(id, token, versioned = true, transcribe = true) {
  const socket = new WebSocket(versioned ? `${wsBase}/ws/v1/meetings/${id}/audio` : `${wsBase}/ws/record/${id}`, { headers: { Cookie: session.cookie() } })
  const messages = []
  socket.on('message', data => messages.push(JSON.parse(data)))
  await once(socket, 'open'); socket.send(JSON.stringify({ type: 'start', token, ...(versioned ? { protocolVersion: 1, audio: { format: 'pcm_s16le', sampleRate: 16000, channels: 1, bitsPerSample: 16 } } : {}) }))
  await waitFor(() => messages.find(m => m.type === 'ready' || m.type === 'error'), 'recorder handshake')
  const ready = messages.find(m => m.type === 'ready')
  if(ready && transcribe) assert.equal((await api(`/meetings/${id}/transcription/start`, 'POST')).status,200)
  let seq = 0, offset = ready?.offsetBytes || 0
  const sendAudio = (size = 3200) => { const pcm = new ArrayBuffer(size); socket.send(versioned ? encodeAudioFrame(pcm, seq++, offset) : pcm); offset += size }
  return { socket, messages, sendAudio }
}
async function snapshot(id) { return (await api(`/meetings/${id}`)).body }
async function eventStream(path) {
  const controller = new AbortController()
  const response = await fetch(`${base}/api/v1${path}`, { signal: controller.signal, headers: { Cookie: session.cookie() } })
  assert.equal(response.status, 200)
  const events = [], decoder = new TextDecoder(); let pending = ''
  const reading = (async () => { try { for await (const chunk of response.body) { pending += decoder.decode(chunk, { stream: true }); let boundary; while ((boundary = pending.indexOf('\n\n')) >= 0) { const part = pending.slice(0, boundary); pending = pending.slice(boundary + 2); events.push(part) } } } catch (e) { if (e.name !== 'AbortError') throw e } })()
  return { events, close: async () => { controller.abort(); await reading } }
}

assert.equal((await api('/health')).status, 200)
assert.equal((await api('/meetings', 'POST', { title: ' ', context: '' })).status, 400)
assert.equal((await api('/meetings/missing')).status, 404)
const requestId = randomUUID(), clientToken = randomBytes(32).toString('hex')
const payload = { title: '幂等创建协议验证', source: 'WEB', clientRequestId: requestId, recorderToken: clientToken }
const attempt = await api('/meetings', 'POST', payload), retry = await api('/meetings', 'POST', payload)
assert.equal(attempt.body.meeting.id, retry.body.meeting.id)
assert.equal(retry.body.meeting.source, 'WEB')
assert.equal((await api('/meetings', 'POST', { ...payload, title: '不同内容' })).status, 409)
assert.equal((await api('/meetings', 'POST', { title: '缺少凭证', clientRequestId: randomUUID() })).status, 400)
assert.equal((await api('/meetings/missing/transcript')).status, 404)
assert.equal((await api('/meetings/missing/events')).status, 404)
assert.equal((await api('/meetings', 'POST', { title: '来源错误', source: 'BAD' })).status, 400)
// Recording and viewing alone must not activate paid transcription.
const manual = await create('默认只录音');
assert.equal((await api(`/meetings/${manual.meeting.id}/transcription/start`,'POST')).status,409)
const quiet = await recorder(manual.meeting.id,manual.recorderToken,true,false);
quiet.sendAudio(32000);
await waitFor(()=>quiet.messages.some(m=>m.type==='ack'),'audio saved without ASR');
let quietDetail=await snapshot(manual.meeting.id);
assert.equal(quietDetail.transcribing,false);assert.equal(quietDetail.draft,'');assert.equal(quietDetail.segments.length,0);
assert.equal((await api(`/meetings/${manual.meeting.id}/transcription/start`,'POST')).status,200);
assert.equal((await api(`/meetings/${manual.meeting.id}/transcription/start`,'POST')).status,409);
quiet.sendAudio(32000);
await waitFor(async()=>(await snapshot(manual.meeting.id)).draft,'manual realtime text');
quiet.socket.send(JSON.stringify({type:'stop'}));await waitFor(()=>quiet.messages.some(m=>m.type==='finished'),'manual stop');
await waitFor(async()=>(await snapshot(manual.meeting.id)).meeting.summaryStatus==='DONE','manual summary');
assert.equal((await snapshot(manual.meeting.id)).segments[0].startMs,32000/32);
assert.equal((await api(`/meetings/${manual.meeting.id}/transcription/start`,'POST')).status,409);
const archive=await create('未转录录音');const only=await recorder(archive.meeting.id,archive.recorderToken,true,false);
only.sendAudio(32000);await waitFor(()=>only.messages.some(m=>m.type==='ack'),'archive saved');
only.socket.send(JSON.stringify({type:'stop'}));await waitFor(()=>only.messages.some(m=>m.type==='finished'),'archive stopped');
quietDetail=await snapshot(archive.meeting.id);assert.equal(quietDetail.segments.length,0);assert.equal(quietDetail.meeting.summaryStatus,'IDLE');
assert.equal((await api(`/meetings/${archive.meeting.id}/retranscribe`,'POST')).status,200);
await waitFor(async()=>(await snapshot(archive.meeting.id)).meeting.status==='COMPLETED','manual archive transcription');
assert.ok((await snapshot(archive.meeting.id)).segments.length);
const list = await eventStream('/events')
const summaryCreation = { title: '跨设备实时转录集成测试', source: 'WEB', clientRequestId: randomUUID(), recorderToken: randomBytes(32).toString('hex') }
const created = (await api('/meetings', 'POST', summaryCreation)).body
const { id } = created.meeting, token = created.recorderToken
await waitFor(() => list.events.some(e => e.includes(id)), 'list sees new meeting')
const viewer = await eventStream(`/meetings/${id}/events`)
const forged = await recorder(id, 'forged-token')
assert.ok(forged.messages.some(m => m.type === 'error'))
assert.equal((await snapshot(id)).meeting.status, 'WAITING')
const live = await recorder(id, token)
assert.ok(live.messages.some(m => m.type === 'ready'))
const duplicate = await recorder(id, token)
assert.ok(duplicate.messages.some(m => m.type === 'error'))
assert.equal((await snapshot(id)).meeting.status, 'RECORDING')
live.sendAudio(32000)
await waitFor(async () => (await snapshot(id)).draft === '正在讨论项目', 'interim draft')
await waitFor(() => viewer.events.some(e => e.includes('正在讨论项目')), 'other device sees draft')
assert.equal((await snapshot(id)).segments.length, 0)
await waitFor(() => live.messages.some(m => m.type === 'ack' && m.committedBytes === 32000 && m.sequence === 0), 'durable byte ack')
live.socket.send(JSON.stringify({ type: 'ping' })); await waitFor(() => live.messages.some(m => m.type === 'pong'), 'recording heartbeat')
live.socket.send(JSON.stringify({ type: 'stop' }))
await waitFor(() => live.messages.some(m => m.type === 'finished'), 'final sentence before finish')
let result = await snapshot(id)
assert.equal(result.meeting.status, 'COMPLETED'); assert.equal(result.draft, '')
assert.equal(result.segments.length, 1); assert.equal(result.segments[0].text, '会议确认项目按计划推进。')
assert.equal(result.meeting.audioBytes, 32000)
const audioResponse = await fetch(`${base}/api/v1/meetings/${id}/audio`, { headers: { Cookie: session.cookie() } })
assert.equal(audioResponse.status, 200)
const wav = Buffer.from(await audioResponse.arrayBuffer())
assert.equal(wav.length, 32044); assert.equal(wav.subarray(0, 4).toString(), 'RIFF'); assert.equal(wav.readUInt32LE(40), 32000)
assert.equal((await api(`/meetings/${id}/summary`, 'POST', undefined, 'forged')).status, 403)
await waitFor(async () => (await snapshot(id)).meeting.summaryStatus === 'DONE', 'automatic cloud summary without a POST')
assert.ok((await snapshot(id)).meeting.summary.includes('未明确'))
assert.equal((await snapshot(id)).meeting.title,'项目进度与方案复核')
assert.equal((await api('/meetings')).body.find(m=>m.id===id).title,'项目进度与方案复核')
const retryAfterTitle = await api('/meetings', 'POST', summaryCreation)
assert.equal(retryAfterTitle.status,200);assert.equal(retryAfterTitle.body.meeting.id,id);assert.equal(retryAfterTitle.body.meeting.title,'项目进度与方案复核')
assert.equal((await api('/meetings', 'POST', {...summaryCreation,title:'不同内容'})).status,409)
await waitFor(()=>viewer.events.some(e=>e.includes('项目进度与方案复核')), 'AI title in detail SSE')
await waitFor(()=>list.events.filter(e=>e.includes(id)).length>=2, 'list refresh after summary title')
assert.equal((await api(`/meetings/${id}/summary`, 'POST', undefined, token)).status, 200)
await waitFor(async () => (await snapshot(id)).meeting.summaryStatus === 'DONE', 'manual summary regeneration')
assert.equal((await api(`/meetings/${id}/retranscribe`, 'POST', undefined, token)).status, 200)
await waitFor(async () => (await snapshot(id)).meeting.status === 'COMPLETED', 'retranscription')
result = await snapshot(id)
assert.equal(result.segments.length, 1); assert.equal(result.meeting.summaryStatus, 'IDLE')
const reopen = await recorder(id, token)
assert.ok(reopen.messages.some(m => m.type === 'error')); assert.equal((await snapshot(id)).meeting.status, 'COMPLETED')
const interrupted = await create('断线与续录测试')
const first = await recorder(interrupted.meeting.id, interrupted.recorderToken)
first.sendAudio(); await delay(150); first.socket.terminate()
await waitFor(async () => (await snapshot(interrupted.meeting.id)).meeting.status === 'INTERRUPTED', 'disconnect detected')
assert.equal((await snapshot(interrupted.meeting.id)).meeting.audioBytes, 3200)
assert.equal((await snapshot(interrupted.meeting.id)).meeting.summaryStatus, 'IDLE')
const resumed = await recorder(interrupted.meeting.id, interrupted.recorderToken)
resumed.sendAudio(); await delay(150); resumed.socket.send(JSON.stringify({ type: 'stop' }))
await waitFor(() => resumed.messages.some(m => m.type === 'finished'), 'resumed recording')
result = await snapshot(interrupted.meeting.id)
assert.equal(result.meeting.audioBytes, 6400); assert.equal(result.segments[0].startMs, 100)
const failure = await create('云端失败音频保留测试', 'FAIL_ASR_TEST')
const fail = await recorder(failure.meeting.id, failure.recorderToken)
fail.sendAudio()
await waitFor(async () => !!(await snapshot(failure.meeting.id)).transcriptionError, 'cloud failure does not stop recording')
assert.equal((await snapshot(failure.meeting.id)).meeting.status,'RECORDING')
fail.socket.send(JSON.stringify({type:'stop'}));await waitFor(()=>fail.messages.some(m=>m.type==='finished'),'save after cloud failure')
assert.equal((await snapshot(failure.meeting.id)).meeting.audioBytes, 3200)
const original = (await snapshot(failure.meeting.id)).segments
await api(`/meetings/${failure.meeting.id}/retranscribe`, 'POST', undefined, failure.recorderToken)
await waitFor(async () => (await snapshot(failure.meeting.id)).meeting.status !== 'REPROCESSING', 'failed retranscription')
assert.deepEqual((await snapshot(failure.meeting.id)).segments, original)
assert.ok((await snapshot(failure.meeting.id)).meeting.error.includes('原转录已保留'))
const invalid = await create('错误帧协议验证')
const invalidRecorder = await recorder(invalid.meeting.id, invalid.recorderToken)
invalidRecorder.socket.send(encodeAudioFrame(new ArrayBuffer(3200), 1, 0))
await waitFor(() => invalidRecorder.messages.some(m => m.code === 'INVALID_PROTOCOL'), 'invalid sequence rejected')
await waitFor(async () => (await snapshot(invalid.meeting.id)).meeting.status === 'INTERRUPTED', 'invalid frame stops session')
assert.equal((await snapshot(invalid.meeting.id)).meeting.audioBytes, 0)
const legacy = await create('初版眼镜兼容接口验证')
const legacyRecorder = await recorder(legacy.meeting.id, legacy.recorderToken, false)
legacyRecorder.sendAudio(); legacyRecorder.socket.send(JSON.stringify({ type: 'stop' }))
await waitFor(() => legacyRecorder.messages.some(m => m.type === 'finished'), 'legacy raw PCM compatibility')
const exportResponse = await fetch(`${base}/api/v1/meetings/${id}/export`, { headers: { Cookie: session.cookie() } })
assert.equal(exportResponse.status, 200); assert.ok((await exportResponse.text()).includes('会议确认项目'))
await list.close(); await viewer.close()
console.log('PASS: v1 idempotent creation, MM01 sequence and offset, durable ack, heartbeat, legacy compatibility, REST validation, live list, independent SSE viewer, recorder ownership, duplicate prevention, drafts, tail flush, WAV, summary, retranscription, disconnect/resume, cloud failure preservation, Markdown export')
