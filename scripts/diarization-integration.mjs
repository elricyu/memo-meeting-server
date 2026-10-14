// Isolated synthetic test. Verifies actual WS adapter tail and owner-scoped speaker APIs.
import assert from 'node:assert/strict'
import { once } from 'node:events'
import WebSocket from '../frontend/node_modules/ws/wrapper.mjs'
import { Session } from './session.mjs'
import { encodeAudioFrame } from '../frontend/public/audio-frame.js'
const base=process.env.TEST_URL,session=new Session(base),other=new Session(base)
await session.register('diarization');await other.register('other_speaker')
const config=await session.api('/config');const separates=config.body.diarization;assert.equal(separates,!config.body.asrModel.endsWith('-streaming'));assert.equal(config.body.asrProvider,process.env.TEST_PROVIDER || 'DEEPGRAM')
const created=(await session.api('/meetings','POST',{title:'隔离多人转录验证'})).body
const id=created.meeting.id,ws=new WebSocket(`${base.replace('http','ws')}/ws/v1/meetings/${id}/audio`,{headers:{Cookie:session.cookie()}}),messages=[]
ws.on('message',data=>messages.push(JSON.parse(data)))
async function wait(fn,label){for(let i=0;i<100;i++){const result=await fn();if(result)return result;await new Promise(r=>setTimeout(r,100))}throw new Error(`Diarization timeout ${label}`)}
try {
  await once(ws,'open');ws.send(JSON.stringify({type:'start',protocolVersion:1,token:created.recorderToken,audio:{format:'pcm_s16le',sampleRate:16000,channels:1,bitsPerSample:16}}))
  await wait(()=>messages.some(m=>m.type==='ready'),'ready')
  ws.send(encodeAudioFrame(new ArrayBuffer(32000),0,0));await wait(()=>messages.some(m=>m.type==='ack'),'ack')
  if(separates && ['SONIOX','TENCENT','DOUBAO'].includes(process.env.TEST_PROVIDER)) {
    const live=await wait(async()=>{const d=(await session.api(`/meetings/${id}`)).body;return d.segments?.[0]?.text==='项目' && d.speakers?.length===1?d:false},'live speaker before stop');
    assert.equal(live.meeting.status,'RECORDING');assert.equal(live.speakers.length,1);
    const speaker=live.speakers[0].id;
    const streamAbort=new AbortController();let snapshots='';
    const stream=await fetch(`${base}/api/v1/meetings/${id}/events`,{headers:{Cookie:session.cookie()},signal:streamAbort.signal});
    const read=(async()=>{try{for await(const chunk of stream.body)snapshots+=Buffer.from(chunk).toString()}catch{}})();
    assert.equal((await other.api(`/meetings/${id}/speakers/${encodeURIComponent(speaker)}`,'POST',{name:'越权'})).status,404);
    assert.equal((await session.api(`/meetings/${id}/speakers/${encodeURIComponent(speaker)}`,'POST',{name:'直播张三'})).status,200);
    await wait(()=>snapshots.includes('直播张三'),'name SSE broadcast');streamAbort.abort();await read;
    ws.send(encodeAudioFrame(new ArrayBuffer(32000),1,32000));
    const following=await wait(async()=>{const d=(await session.api(`/meetings/${id}`)).body;return d.segments?.length===2?d:false},'next live speaker');
    assert.equal(following.speakers[0].name,'直播张三');assert.equal(following.segments[0].text,'项目按计划推进。');
  }
  if(!separates){await wait(async()=>(await session.api(`/meetings/${id}`)).body.draft?.includes('项目'),'live single-pass draft');ws.send(encodeAudioFrame(new ArrayBuffer(32000),1,32000));}
  ws.send(JSON.stringify({type:'stop'}));await wait(()=>messages.some(m=>m.type==='finished'),'final tail')
  await wait(async()=>(await session.api(`/meetings/${id}`)).body.meeting.summaryStatus==='DONE','automatic summary after stop');
  let detail=(await session.api(`/meetings/${id}`)).body;assert.equal(detail.meeting.status,'COMPLETED');assert.equal(detail.segments.length,2);assert.equal(detail.speakers.length,separates?2:0)
  assert.equal(detail.segments[0].text,'项目按计划推进。');if(separates)assert.notEqual(detail.segments[0].speakerId,detail.segments[1].speakerId);else{assert.ok(detail.segments.every(s=>!s.speakerId));console.log('PASS: single-pass partial text, no fabricated speaker IDs, fragmented gzip final tail');process.exit(0)}
  const speaker=detail.speakers[0].id;
  assert.equal((await other.api(`/meetings/${id}/speakers/${encodeURIComponent(speaker)}`,'POST',{name:'越权姓名'})).status,404)
  assert.equal((await session.api(`/meetings/${id}/speakers/${encodeURIComponent(speaker)}`,'POST',{name:'张三'})).status,200)
  detail=(await session.api(`/meetings/${id}`)).body;assert.equal(detail.speakers[0].name,'张三')
  const exported=await(await fetch(`${base}/api/v1/meetings/${id}/export`,{headers:{Cookie:session.cookie()}})).text();assert.match(exported,/张三：项目按计划推进/)
  assert.equal((await session.api(`/meetings/${id}/summary`,'POST')).status,200)
  await wait(async()=>(await session.api(`/meetings/${id}`)).body.meeting.summaryStatus==='DONE','summary')
  await session.api(`/meetings/${id}/speakers/${encodeURIComponent(speaker)}`,'POST',{name:'李四'});assert.equal((await session.api(`/meetings/${id}`)).body.meeting.summaryStatus,'IDLE')
  console.log('PASS: realtime provider parameters/auth, speaker turns, live owner-only names + SSE (Soniox/Tencent/Doubao), final tail, Markdown names, summary invalidation')
} finally {ws.terminate()}
