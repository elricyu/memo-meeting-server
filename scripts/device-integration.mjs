import assert from 'node:assert/strict'
import { randomUUID,randomBytes } from 'node:crypto'
import { once } from 'node:events'
import WebSocket from '../frontend/node_modules/ws/wrapper.mjs'
import { Session,native } from './session.mjs'
import { encodeAudioFrame } from '../frontend/public/audio-frame.js'
const base=process.env.TEST_URL
const alice=new Session(base),bob=new Session(base)
await alice.register('owner_a');await bob.register('owner_b')
const token=randomBytes(32).toString('hex'),clientId=`rv1106b-${randomUUID()}`,mac=randomBytes(6).toString('hex').match(/../g).join(':')
const pairBody={clientId,mac,displayName:'RV1106B 测试眼镜',platform:'RV1106B',deviceToken:token,clientRequestId:randomUUID()}
const pair=await native(base,'/devices/pairings','POST',pairBody);assert.equal(pair.status,200)
assert.equal((await native(base,'/devices/pairings','POST',pairBody)).body.pairingId,pair.body.pairingId)
assert.equal((await native(base,'/meetings','POST',{title:'未绑定设备'},token)).status,401)
assert.equal((await native(base,`/devices/pairings/${pair.body.pairingId}`,'GET',undefined,'forged')).status,404)
assert.equal((await native(base,`/devices/pairings/${pair.body.pairingId}`,'GET',undefined,token)).body.status,'PENDING')
const preview=await alice.api(`/devices/pairings/preview?code=${pair.body.pairingCode}`);assert.equal(preview.status,200);assert.equal(preview.body.clientId,clientId);assert.equal(preview.body.mac,mac.toUpperCase())
assert.equal((await alice.api('/devices/bind','POST',{code:pair.body.pairingCode})).status,200)
assert.equal((await bob.api('/devices/bind','POST',{code:pair.body.pairingCode})).status,404)
const poll=await native(base,`/devices/pairings/${pair.body.pairingId}`,'GET',undefined,token);assert.equal(poll.body.status,'BOUND');assert.equal(poll.body.owner.id,alice.user.id)
const devices=await alice.api('/devices');assert.equal(devices.body.length,1);assert.equal(devices.body[0].clientId,clientId);assert.equal(devices.body[0].mac,mac.toUpperCase());assert.ok(!JSON.stringify(devices.body).includes(token))
assert.equal((await bob.api('/devices')).body.length,0)
const web=await alice.api('/meetings','POST',{title:'用户网页会议'})
const created=await native(base,'/meetings','POST',{title:'绑定眼镜创建的会议',ownerId:bob.user.id},token);assert.equal(created.status,200)
const id=created.body.meeting.id;assert.equal(created.body.meeting.ownerId,alice.user.id);assert.equal(created.body.meeting.deviceId,pair.body.deviceId);assert.equal(created.body.meeting.source,'GLASSES')
assert.equal((await alice.api(`/meetings/${id}`)).status,200)
for(const path of [`/meetings/${id}`,`/meetings/${id}/audio`,`/meetings/${id}/transcript`,`/meetings/${id}/events`,`/meetings/${id}/export`])assert.equal((await bob.api(path)).status,404)
assert.equal((await bob.api(`/meetings/${id}/finish`,'POST')).status,404)
assert.equal((await native(base,`/meetings/${web.body.meeting.id}`,'GET',undefined,token)).status,404)
const ownList=await native(base,'/meetings','GET',undefined,token);assert.equal(ownList.body.length,1);assert.equal(ownList.body[0].id,id)
const ws=new WebSocket(`${base.replace('http','ws')}/ws/v1/meetings/${id}/audio`,{headers:{Authorization:`Bearer ${token}`}})
const messages=[];ws.on('message',data=>messages.push(JSON.parse(data)))
await once(ws,'open');ws.send(JSON.stringify({type:'start',protocolVersion:1,audio:{format:'pcm_s16le',sampleRate:16000,channels:1,bitsPerSample:16}}))
for(let i=0;i<100 && !messages.some(m=>m.type==='ready');i++)await new Promise(r=>setTimeout(r,100))
assert.ok(messages.some(m=>m.type==='ready'));assert.equal((await native(base,`/meetings/${id}/transcription/start`,'POST',undefined,token)).status,403);assert.equal((await bob.api(`/meetings/${id}/transcription/start`,'POST')).status,404);assert.equal((await alice.api(`/meetings/${id}`)).body.transcribing,false);ws.send(encodeAudioFrame(new ArrayBuffer(32000),0,0))
for(let i=0;i<100 && !messages.some(m=>m.type==='ack');i++)await new Promise(r=>setTimeout(r,100))
assert.ok(messages.some(m=>m.type==='ack'))
const deviceStreamAbort = new AbortController()
const streamResponse = await fetch(`${base}/api/v1/meetings/${id}/events`,{headers:{Authorization:`Bearer ${token}`},signal:deviceStreamAbort.signal});assert.equal(streamResponse.status,200)
const streamReader = streamResponse.body.getReader();assert.ok(!(await streamReader.read()).done)
assert.equal((await bob.api(`/devices/${pair.body.deviceId}/unbind`,'POST')).status,404)
assert.equal((await alice.api(`/devices/${pair.body.deviceId}/unbind`,'POST')).status,200)
const streamTimer=setTimeout(()=>deviceStreamAbort.abort(),5000)
try { let ended=false; for(let i=0;i<10;i++){if((await streamReader.read()).done){ended=true;break}}assert.ok(ended,'device SSE must close on revoke') } finally {clearTimeout(streamTimer);deviceStreamAbort.abort()}
for(let i=0;i<100 && !messages.some(m=>m.type==='error');i++)await new Promise(r=>setTimeout(r,100))
assert.ok(messages.some(m=>m.message?.includes('解绑')))
assert.equal((await native(base,'/meetings','POST',{title:'解绑设备'},token)).status,401)
assert.equal((await alice.api(`/meetings/${id}`)).body.meeting.status,'INTERRUPTED')
assert.equal((await alice.api(`/meetings/${id}`)).body.meeting.ownerId,alice.user.id)
assert.equal((await bob.api(`/meetings/${id}`)).status,404)
const freshToken=randomBytes(32).toString('hex')
const fresh=await native(base,'/devices/pairings','POST',{...pairBody,deviceToken:freshToken,clientRequestId:randomUUID()});assert.equal(fresh.status,200)
assert.equal((await bob.api('/devices/bind','POST',{code:fresh.body.pairingCode})).status,200)
const later=await native(base,'/meetings','POST',{title:'重新绑定后的会议'},freshToken);assert.equal(later.body.meeting.ownerId,bob.user.id)
assert.equal((await bob.api(`/meetings/${id}`)).status,404)
// A bound device's normal stop must generate the owner's summary without a browser POST.
const completedId=later.body.meeting.id,normal=new WebSocket(`${base.replace('http','ws')}/ws/v1/meetings/${completedId}/audio`,{headers:{Authorization:`Bearer ${freshToken}`}}),normalMessages=[];
normal.on('message',data=>normalMessages.push(JSON.parse(data)));
async function wait(fn,label){for(let i=0;i<100;i++){if(await fn())return;await new Promise(r=>setTimeout(r,100))}throw new Error(label)}
await once(normal,'open');normal.send(JSON.stringify({type:'start',protocolVersion:1,audio:{format:'pcm_s16le',sampleRate:16000,channels:1,bitsPerSample:16}}));
await wait(()=>normalMessages.some(m=>m.type==='ready'),'device ready');assert.equal((await bob.api(`/meetings/${completedId}/transcription/start`,'POST')).status,200);normal.send(encodeAudioFrame(new ArrayBuffer(32000),0,0));
await wait(()=>normalMessages.some(m=>m.type==='ack'),'device audio saved');normal.send(JSON.stringify({type:'stop'}));
await wait(()=>normalMessages.some(m=>m.type==='finished'),'device finished');
await wait(async()=>(await bob.api(`/meetings/${completedId}`)).body.meeting.summaryStatus==='DONE','automatic device summary');
const automatic=(await bob.api(`/meetings/${completedId}`)).body;
assert.equal(automatic.meeting.title,'项目进度与方案复核');assert.equal(automatic.meeting.ownerId,bob.user.id);assert.equal((await alice.api(`/meetings/${completedId}`)).status,404);normal.terminate();
// Authenticated browser mutations require CSRF, even with a valid session cookie.
const csrf=await fetch(`${base}/api/v1/meetings`,{method:'POST',headers:{Cookie:alice.cookie(),'Content-Type':'application/json'},body:JSON.stringify({title:'缺少 CSRF'})});assert.equal(csrf.status,403)
const logout=await alice.api('/auth/logout','POST');assert.equal(logout.status,200);assert.equal((await alice.api('/meetings')).status,401)
console.log('PASS: account sessions, CSRF, clientId/MAC persistence, pair preview/approval/polling, device owner assignment, cross-user isolation, device scope, active revoke, rebind without historical ownership transfer')
