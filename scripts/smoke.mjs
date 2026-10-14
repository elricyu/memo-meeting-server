import assert from 'node:assert/strict'
import { WebSocket } from '../frontend/node_modules/ws/wrapper.mjs'
const base = process.env.TEST_BASE || 'http://127.0.0.1:19090'
async function api(path, options) { const r = await fetch(base + '/api' + path, options); const body = await r.json(); assert.ok(r.ok, JSON.stringify(body)); return body }
const post = body => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
const sleep = ms => new Promise(r => setTimeout(r, ms))
async function poll(id, status) { for (let i = 0; i < 100; i++) { const d = await api('/meetings/' + id); if (d.meeting.status === status) return d; await sleep(100) } throw new Error('Timed out: ' + status) }
async function connect(id, token) {
  const socket = new WebSocket(base.replace('http', 'ws') + '/ws/record/' + id)
  const messages = []; const waiters = []
  socket.on('message', data => { const value = JSON.parse(data); messages.push(value); waiters.splice(0).forEach(fn => fn()) })
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject) })
  socket.send(JSON.stringify({ type: 'start', token }))
  async function wait(type) {
    for (let i = 0; i < 100; i++) { const message = messages.find(m => m.type === type); if (message) return message; await Promise.race([sleep(100), new Promise(resolve => waiters.push(resolve))]) }
    throw new Error('Socket event timed out: ' + type)
  }
  return { socket, wait }
}
const created = await api('/meetings', post({ title: '跨端协议联调', context: 'Memo Meeting' }))
const id = created.meeting.id, token = created.recorderToken
const spoofed = await connect(id, 'forged'); await spoofed.wait('error')
assert.equal((await api('/meetings/' + id)).meeting.status, 'WAITING')
const recording = await connect(id, token); await recording.wait('ready')
const duplicate = await connect(id, token); await duplicate.wait('error')
assert.equal((await api('/meetings/' + id)).meeting.status, 'RECORDING')
const abort = new AbortController()
const sse = await fetch(base + '/api/meetings/' + id + '/events', { signal: abort.signal })
const reader = sse.body.getReader(); let event = await reader.read(); assert.match(new TextDecoder().decode(event.value), /snapshot/)
recording.socket.send(Buffer.alloc(3200))
let snapshot
for (let i = 0; i < 30; i++) { snapshot = await api('/meetings/' + id); if (snapshot.segments.length) break; await sleep(100) }
assert.equal(snapshot.segments[0].text, '正在验证实时转录。')
recording.socket.send(JSON.stringify({ type: 'stop' })); await recording.wait('finished')
const done = await poll(id, 'COMPLETED'); assert.equal(done.segments.length, 2); assert.equal(done.meeting.audioBytes, 3200)
abort.abort(); await reader.cancel().catch(() => {})
const wav = new Uint8Array(await (await fetch(base + '/api/meetings/' + id + '/audio')).arrayBuffer())
assert.equal(wav.length, 3244); assert.equal(new DataView(wav.buffer).getUint32(40, true), 3200)
const auth = { method: 'POST', headers: { 'X-Recorder-Token': token } }
await api('/meetings/' + id + '/summary', auth)
for (let i = 0; i < 50; i++) { snapshot = await api('/meetings/' + id); if (snapshot.meeting.summaryStatus === 'DONE') break; await sleep(100) }
assert.match(snapshot.meeting.summary, /协议联调/)
await api('/meetings/' + id + '/retranscribe', auth); await poll(id, 'COMPLETED')
assert.equal((await api('/meetings/' + id)).meeting.summary, '')
const reopened = await connect(id, token); await reopened.wait('error'); assert.equal((await api('/meetings/' + id)).meeting.status, 'COMPLETED')
const second = await api('/meetings', post({ title: '断线续录联调' }))
const s2 = await connect(second.meeting.id, second.recorderToken); await s2.wait('ready'); s2.socket.send(Buffer.alloc(3200)); await sleep(150); s2.socket.terminate()
await poll(second.meeting.id, 'INTERRUPTED')
const s3 = await connect(second.meeting.id, second.recorderToken); await s3.wait('ready'); s3.socket.send(Buffer.alloc(3200)); s3.socket.send(JSON.stringify({ type: 'stop' })); await s3.wait('finished')
const resumed = await poll(second.meeting.id, 'COMPLETED'); assert.equal(resumed.meeting.audioBytes, 6400); assert.ok(resumed.segments.some(s => s.startMs >= 100))
console.log('PASS: forged token, duplicate recorder, live REST/SSE, final sentence, WAV, summary, replay, rejected reopen, interrupted resume')
