// Explicit test fixture only. Never imported by production code.
import http from 'node:http'
import { gzipSync, gunzipSync } from 'node:zlib'
import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
import { WebSocketServer } from '../frontend/node_modules/ws/wrapper.mjs'
const port = Number(process.env.MOCK_PORT || 19100)
const server = http.createServer(async (req, res) => {
  if (req.url === '/chat') {
    let body = ''; for await (const part of req) body += part
    const request = JSON.parse(body)
    if (!request.messages?.length) { res.writeHead(400); return res.end() }
    res.writeHead(200, { 'Content-Type': 'application/json' })
    const summary='## 摘要\n测试会议讨论了项目进度。\n\n## 已明确的结论\n按计划推进 [0秒]。\n\n## 待办事项\n任务：复核方案；负责人：未明确；截止时间：未明确。\n\n## 未决问题\n未提及。'
    const content=request.messages[0]?.content?.includes('"title"')?JSON.stringify({title:'项目进度与方案复核',summary}):summary
    res.end(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content } }] }))
  } else if (req.url === '/health') { res.end('ok') }
  else { res.writeHead(404); res.end() }
})
const wss = new WebSocketServer({ server })
wss.on('connection', (ws, req) => {
  if(req.url.startsWith('/doubao')) {
    const streaming=req.url.includes('streaming'),twoPass=req.url.includes('twopass');
    if(req.headers['x-api-key']!=='explicit-protocol-test-key' || req.headers.authorization || req.headers['x-api-resource-id']!=='volc.seedasr.sauc.duration' || !req.headers['x-api-request-id'] || req.headers['x-api-sequence']!=='-1')return ws.close(1008,'wrong Doubao headers');
    let started=false,bytes=0,firstSent=false,nextSent=false,sequence=0;
    const item=(text,speaker,start_time,end_time,definite=true)=>({text,start_time,end_time,definite,additions:speaker==null?{}:{speaker_id:speaker}});
    const send=(utterances,final=false,fragment=false)=>{
      const payload=gzipSync(Buffer.from(JSON.stringify({audio_info:{duration:bytes/32},result:{utterances,text:utterances.map(u=>u.text).join('')}})));
      const head=Buffer.alloc(12);head[0]=0x11;head[1]=final?0x93:0x91;head[2]=0x11;head.writeInt32BE(final?-(++sequence):++sequence,4);head.writeUInt32BE(payload.length,8);
      const packet=Buffer.concat([head,payload]);
      if(fragment){ws.send(packet.subarray(0,7),{binary:true,fin:false});ws.send(packet.subarray(7),{binary:true,fin:true})}else ws.send(packet);
    };
    ws.on('message',(data,binary)=>{
      try {
        assert.ok(binary);assert.equal(data[0],0x11);assert.equal(data.readUInt32BE(4),data.length-8);
        const type=data[1]>>4,terminal=!!(data[1]&2),payload=gunzipSync(data.subarray(8));
        if(type===1){
          assert.equal(data[2],0x11);const config=JSON.parse(payload);
          assert.equal(config.request.model_name,'bigmodel');assert.equal(config.request.enable_nonstream,twoPass);
          assert.equal(config.request.enable_speaker_info,!streaming);assert.equal(config.request.show_utterances,true);assert.equal(config.request.result_type,'full');assert.equal(config.audio.rate,16000);assert.equal(config.audio.channel,1);
          started=true;return; // Optimized mode need not return a configuration ACK.
        }
        assert.equal(type,2);assert.equal(data[2],1);assert.ok(started);assert.ok(payload.length>0 && payload.length<=6400);assert.ok(payload.every(v=>v===0));bytes+=payload.length;
        if(terminal){assert.equal(bytes,64000);return setTimeout(()=>{send([item('项目按计划推进。',streaming?null:'0',0,500),item('收到，我负责复核。',streaming?null:'1',500,1500)],true,true);ws.close(1000,'finished')},100)}
        assert.equal(payload.length,6400);
        if(!firstSent){firstSent=true;send([item('项目',null,0,200,!streaming)]);return setTimeout(()=>send([item('项目',streaming?null:'0',0,200,!streaming),item('按计划',null,200,500,false)],false,true),80)}
        if(bytes>32000 && !nextSent){nextSent=true;send([item('项目按计划推进。',streaming?null:'0',0,500,!streaming),item('收到',streaming?null:'1',500,800,!streaming),item('，我负责',null,800,1000,false)])}
      }catch(error){console.error('Doubao fixture assertion',error.message);ws.close(1008,'wrong Doubao packet/config')}
    });return;
  }
  if(req.url.startsWith('/tencent/1250000000')) {
    const url=new URL(req.url,'http://localhost'),params=url.searchParams,signature=params.get('signature');params.delete('signature');
    const raw=[...params].sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>`${k}=${v}`).join('&');
    const expected=createHmac('sha1','fixtureSecretKey123').update(`${req.headers.host}${url.pathname}?${raw}`).digest('base64');
    if(signature!==expected || req.headers.authorization || params.get('secretid')!=='fixtureSecretId123' || params.get('engine_model_type')!=='16k_zh_en_speaker_2.0' || params.get('voice_format')!=='1' || params.get('needvad')!=='1' || params.get('sentence_strategy')!=='0')return ws.close(1008,'wrong Tencent signature/config');
    const voice_id=params.get('voice_id');let frames=0;
    const item=(sentence_id,sentence,speaker_id,start_time,end_time,sentence_type=1)=>({sentence_id,sentence,speaker_id,start_time,end_time,sentence_type});
    const send=(sentence_list,final=0)=>ws.send(JSON.stringify({code:0,voice_id,sentences:{sentence_list},final}));
    const first=item(0,'项目',-1,0,200);
    ws.send(JSON.stringify({code:0,voice_id}));
    ws.on('message',(data,binary)=>{
      if(binary){frames++;
        if(frames===1){send([first]);return setTimeout(()=>send([{...first,speaker_id:0},item(1,'按计划',0,200,500,0)]),80)}
        return send([item(0,'项目按计划推进。',0,0,500),item(1,'收到',1,500,800),item(2,'，我负责',1,800,1000,0)]);
      }
      if(JSON.parse(data).type==='end')setTimeout(()=>{send([item(0,'项目按计划推进。',0,0,500),item(1,'收到，我负责复核。',1,500,1500)],1);ws.close(1000,'finished')},100);
    });
    return;
  }
  if(req.url.startsWith('/soniox')) {
    if(req.headers.authorization !== 'Bearer explicit-protocol-test-key')return ws.close(1008,'wrong Soniox token');
    let started=false,frames=0;
    const token=(text,speaker,start_ms,end_ms,is_final=true)=>({text,speaker,start_ms,end_ms,is_final});
    ws.on('message',(data,binary)=>{
      if(binary){
        if(!started)return ws.close(1008,'audio before config');
        frames++;
        const tokens=frames===1?[token('项目','1',0,200),token('按计划','1',200,500,false)]:[token('按计划推进。','1',200,500),token('收到','2',500,800),token('，我负责','2',800,1000,false)];
        return ws.send(JSON.stringify({tokens}));
      }
      if(data.toString()==='')return setTimeout(()=>{ws.send(JSON.stringify({tokens:[token('，我负责复核。','2',800,1500)],finished:true}));ws.close(1000,'finished');},100);
      const config=JSON.parse(data);
      if(config.type==='keepalive')return;
      if(config.model!=='stt-rt-v5' || config.audio_format!=='pcm_s16le' || config.sample_rate!==16000 || config.num_channels!==1 || !config.enable_speaker_diarization || config.enable_endpoint_detection || config.api_key)return ws.close(1008,'wrong Soniox config');
      started=true;
    });
    return;
  }
  if(req.url.startsWith('/deepgram')) {
    if(req.headers.authorization !== 'Token explicit-protocol-test-key')return ws.close(1008,'wrong test token');
    const params=new URL(req.url,'http://localhost').searchParams;
    if(params.get('encoding')!=='linear16' || params.get('sample_rate')!=='16000' || params.get('diarize_model')!=='latest')return ws.close(1008,'wrong Deepgram parameters');
    let bytes=0;
    ws.on('message',(data,binary)=>{
      if(binary){bytes+=data.length;return ws.send(JSON.stringify({type:'Results',start:0,duration:bytes/32000,is_final:false,channel:{alternatives:[{transcript:'正在讨论',words:[]}]}}))}
      const input=JSON.parse(data);
      if(input.type==='CloseStream')setTimeout(()=>{
        ws.send(JSON.stringify({type:'Results',start:0,duration:bytes/32000,is_final:true,channel:{alternatives:[{transcript:'项目按计划推进。收到，我负责复核。',words:[{punctuated_word:'项目按计划推进。',start:0,end:0.5,speaker:0},{punctuated_word:'收到，我负责复核。',start:0.5,end:1,speaker:1}]}]}}));
        ws.send(JSON.stringify({type:'Metadata',request_id:'explicit-test-fixture'}));ws.close(1000,'finished');
      },100);
    });
    return;
  }
  let task, bytes = 0, begun = false, emitted = false, failure = false
  const send = (event, payload = {}) => ws.send(JSON.stringify({ header: { event, task_id: task }, payload }))
  const sentence = (text, final = false) => send('result-generated', { output: { sentence: { sentence_id: 1, begin_time: 0, end_time: final ? Math.floor(bytes / 32) : null, text, sentence_end: final } } })
  ws.on('message', (message, binary) => {
    if (binary) {
      if (!begun) return ws.close(1008, 'audio before task-started')
      bytes += message.length
      if (failure) { send('task-failed', {}); return ws.close() }
      if (!emitted) { sentence('正在讨论项目'); emitted = true }
      return
    }
    const request = JSON.parse(message)
    if (request.header.action === 'run-task') {
      task = request.header.task_id
      failure = JSON.stringify(request.payload.input).includes('FAIL_ASR_TEST')
      if (request.payload.parameters.sample_rate !== 16000 || request.payload.parameters.format !== 'pcm') return ws.close(1008, 'wrong PCM config')
      setTimeout(() => { begun = true; send('task-started') }, 40)
    }
    if (request.header.action === 'finish-task') {
      // The final sentence intentionally arrives AFTER stop to catch tail loss.
      setTimeout(() => { if (bytes) sentence('会议确认项目按计划推进。', true); send('task-finished') }, 100)
    }
  })
})
server.listen(port, '127.0.0.1', () => console.log(`Test cloud fixture listening on ${port}`))
