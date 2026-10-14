import { Session } from './session.mjs'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
import WebSocket from '../frontend/node_modules/ws/wrapper.mjs'
import { encodeAudioFrame } from '../frontend/public/audio-frame.js'
const base = process.env.TEST_URL
const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : 'java'
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = fs.openSync(`${process.env.RESTART_DATA_DIR}.log`, 'a')
let app
const session=new Session(base)
function start() {
  app = spawn(java, ['-Xms64m', '-Xmx256m', '-jar', 'backend/target/memo-meeting-0.1.0.jar'], { env: { ...process.env, PORT: new URL(base).port, DATA_DIR: process.env.RESTART_DATA_DIR }, stdio: ['ignore', log, log] })
}
async function ready() {
  for (let i = 0; i < 100; i++) { if (app.exitCode !== null) throw new Error('Restart test server exited'); try { if ((await fetch(`${base}/api/v1/health`)).ok) return } catch {} await delay(100) }
  throw new Error('Restart test startup timed out')
}
async function snapshot(id) { return (await fetch(`${base}/api/v1/meetings/${id}`, { headers: { Cookie: session.cookie() } })).json() }
async function record(id, token) {
  const socket = new WebSocket(`${base.replace('http','ws')}/ws/v1/meetings/${id}/audio`, { headers: { Cookie: session.cookie() } })
  const messages=[];socket.on('message', data => messages.push(JSON.parse(data)))
  await once(socket,'open');socket.send(JSON.stringify({type:'start',protocolVersion:1,token,audio:{format:'pcm_s16le',sampleRate:16000,channels:1,bitsPerSample:16}}))
  await wait(()=>messages.find(m=>m.type==='ready'),'ready')
  return { socket, messages }
}
async function wait(fn,label) {for(let i=0;i<100;i++){const value=await fn();if(value)return value;await delay(100)}throw new Error(`Restart test timeout: ${label}`)}
try {
  start();await ready()
  await session.register('restart')
  const response=await session.api('/meetings','POST',{title:'进程崩溃与落盘恢复验证'})
  const created=response.body;const id=created.meeting.id,token=created.recorderToken
  const first=await record(id,token);first.socket.send(encodeAudioFrame(new ArrayBuffer(32000),0,0))
  await wait(()=>first.messages.some(m=>m.type==='ack' && m.committedBytes===32000),'saved audio acknowledgement')
  const exited=once(app,'exit');app.kill('SIGKILL');await exited;first.socket.terminate()
  start();await ready()
  assert.equal((await session.api('/meetings')).status,401)
  const login=await session.api('/auth/login','POST',{username:session.username,password:'explicit-test-password'});assert.equal(login.status,200)
  const recovered=await snapshot(id);assert.equal(recovered.meeting.status,'INTERRUPTED');assert.equal(recovered.meeting.audioBytes,32000)
  const second=await record(id,token);assert.equal(second.messages.find(m=>m.type==='ready').offsetBytes,32000)
  assert.equal((await snapshot(id)).transcribing,false);assert.equal((await session.api(`/meetings/${id}/transcription/start`,'POST')).status,200)
  second.socket.send(encodeAudioFrame(new ArrayBuffer(3200),0,32000));second.socket.send(JSON.stringify({type:'stop'}))
  await wait(()=>second.messages.some(m=>m.type==='finished'),'resume tail flush')
  const done=await snapshot(id);assert.equal(done.meeting.status,'COMPLETED');assert.equal(done.meeting.audioBytes,35200);assert.equal(done.segments[0].startMs,1000)
  const wav=Buffer.from(await(await fetch(`${base}/api/v1/meetings/${id}/audio`, { headers: { Cookie: session.cookie() } })).arrayBuffer());assert.equal(wav.readUInt32LE(40),35200)
  console.log('PASS: actual JVM crash, persisted H2/WAV recovery, recorder token preserved, resumed byte offset, final sentence timestamp')
} finally {
  if(app && app.exitCode===null){const exited=once(app,'exit');app.kill('SIGTERM');await exited}
  fs.closeSync(log)
}
