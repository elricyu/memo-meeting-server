// Isolated role/bootstrap/persistence test. Keys below are test fixtures only.
import { Session } from './session.mjs'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
const base=process.env.TEST_URL, data=process.env.ADMIN_TEST_DATA_DIR
const java=process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : 'java'
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms))
const log=fs.openSync(`${data}.log`,'a')
let app
const admin=new Session(base), normal=new Session(base)
async function start(username='') {
  app=spawn(java,['-Xms64m','-Xmx256m','-jar','backend/target/memo-meeting-0.1.0.jar'],{env:{...process.env,PORT:new URL(base).port,DATA_DIR:data,ADMIN_USERNAME:username,BIND_ADDRESS:'127.0.0.1',ALLOWED_ORIGINS:base,SESSION_COOKIE_SECURE:'false'},stdio:['ignore',log,log]})
  for(let i=0;i<150;i++) {
    if(app.exitCode!==null)throw new Error('Admin fixture server exited')
    try{if((await fetch(`${base}/api/v1/health`)).ok)return}catch{}
    await delay(100)
  }
  throw new Error('Admin fixture startup timed out')
}
async function stop(){if(app && app.exitCode===null){const exited=once(app,'exit');app.kill('SIGTERM');await exited}}
async function login(session){const response=await session.api('/auth/login','POST',{username:session.username,password:'explicit-test-password'});assert.equal(response.status,200);return response.body.user}
try {
  await start();assert.equal((await admin.register('admin')).role,'USER');assert.equal((await normal.register('ordinary')).role,'USER')
  assert.equal((await admin.api('/admin/ai')).status,403)
  await stop();await start(admin.username)
  assert.equal((await login(admin)).role,'ADMIN');assert.equal((await login(normal)).role,'USER')
  for(const type of ['asr','llm']) {
    const response=await admin.api(`/admin/ai/${type}/test`,'POST',{mode:'ENVIRONMENT'})
    assert.equal(response.status,200);assert.equal(response.body.ok,true);assert.equal(response.body.code,'OK')
    assert.equal((await normal.api(`/admin/ai/${type}/test`,'POST',{mode:'ENVIRONMENT'})).status,403)
  }
  const input={mode:'GLOBAL',provider:'DASHSCOPE_CN',model:'fun-asr-realtime',language:'zh',diarization:false,reuseAsrKey:false,apiKey:'isolated-test-key-not-real',workspaceId:'',vocabularyId:''}
  const saved=await admin.api('/admin/ai/asr','POST',input);assert.equal(saved.status,200);assert.equal(saved.body.keySet,true)
  assert.ok(!JSON.stringify(saved.body).includes(input.apiKey));assert.equal((await normal.api('/admin/ai/asr','POST',input)).status,403)
  assert.equal((await normal.api('/config')).body.asrReady,true)
  assert.equal(fs.statSync(`${data}/ai-master.key`).mode & 0o777,0o600)
  await stop();await start()
  assert.equal((await login(admin)).role,'ADMIN');await login(normal)
  const retained=await admin.api('/admin/ai');assert.equal(retained.status,200);assert.equal(retained.body.asr.keySet,true)
  const keep=await admin.api('/admin/ai/asr','POST',{...input,apiKey:''});assert.equal(keep.status,200);assert.equal(keep.body.ready,true)
  assert.equal((await normal.api('/admin/ai')).status,403)
  console.log('PASS: explicit admin bootstrap, no first-user privilege, real ASR/LLM protocol probes against fixture, normal-user denial, global encrypted credentials and role persist across JVM restarts')
} finally { await stop();fs.closeSync(log) }
