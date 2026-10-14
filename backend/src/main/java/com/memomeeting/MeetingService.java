package com.memomeeting;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MeetingService {
    public record RecordingCompleted(String id) {}
    private final org.springframework.context.ApplicationEventPublisher completionEvents;
    public record Detail(Store.Meeting meeting, List<Store.Segment> segments, String draft,List<Store.Speaker> speakers) {}
    private final Store store;
    private final AudioFiles files;
    private final Events events;
    private final CloudAsr asr;
    private final Settings settings;
    private final AiConfigs ai;
    private final Access access;
    private final Map<String,Recording> recordings=new ConcurrentHashMap<>();
    private final Set<String> busy=ConcurrentHashMap.newKeySet();
    private final Map<String,String> drafts=new ConcurrentHashMap<>();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    public MeetingService(Store store,AudioFiles files,Events events,CloudAsr asr,Settings settings,Access access,AiConfigs ai,org.springframework.context.ApplicationEventPublisher completionEvents) {
        this.store=store;this.files=files;this.events=events;this.asr=asr;this.settings=settings;this.access=access;this.ai=ai;this.completionEvents=completionEvents;
    }
    @PostConstruct public void recover() throws IOException {
        store.recover();
        for(var id:store.allIds()) {
            files.finalizeFile(id);store.audioBytes(id,files.bytes(id));
        }
    }
    public Detail detail(String id){return new Detail(store.get(id),store.segments(id),drafts.getOrDefault(id,""),store.speakers(id));}
    public void changed(String id){events.publish(id,"snapshot",detail(id));var owner=store.get(id).ownerId();if(owner!=null)events.publish("user:"+owner,"changed",Map.of("id",id));}
    public void start(String id,String token,WebSocketSession socket) throws Exception {
        start(id,token,socket,false);
    }
    public void start(String id,String token,WebSocketSession socket,boolean versioned) throws Exception {
        start(id,token,socket,versioned,Access.current());
    }
    public void start(String id,String token,WebSocketSession socket,boolean versioned,Identity identity) throws Exception {
        access.meeting(id,identity);
        if(token!=null && !token.isBlank() && !store.authorized(id,token))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"录音凭证错误");
        var config=ai.resolve(identity.userId(),"asr");
        if(!config.ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"请先在 AI 设置中配置语音识别 Key");
        if(!busy.add(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"已有设备正在录音或处理");
        var meeting=store.get(id);
        if(!Set.of("WAITING","INTERRUPTED","FAILED").contains(meeting.status()) || meeting.summaryStatus().equals("RUNNING")) {
            busy.remove(id);throw new ResponseStatusException(HttpStatus.CONFLICT,"当前记录不能继续录音");
        }
        var recording=new Recording(id,socket,versioned,identity);
        try {
            recordings.put(id,recording);
            org.slf4j.LoggerFactory.getLogger(MeetingService.class).info("recording_connecting meeting={} session={} version={}",id,recording.epoch,versioned?1:0);
            store.state(id,"CONNECTING","");changed(id);
            recording.file=files.open(id);recording.bytes=recording.file.length()-44;recording.offset=recording.bytes/32;recording.checkpointBytes=recording.bytes;
            var connected=asr.connect(config,meeting.context(), s->recording.result(s));
            synchronized(recording) {
                if(recording.closed.get()) {connected.abort();throw new IOException("记录端已断开");}
                recording.cloud=connected;
            }
            recording.cloud.done.whenCompleteAsync((v,error)-> {
                if(!recording.finishing.get() && !recording.closed.get()) recording.fail("云端识别中断，已接收的音频已保存，可继续录音或重新转录。");
            },workers);
            if(!socket.isOpen() || recording.closed.get()) throw new IOException("记录端已断开");
            synchronized(recording) {
                if(recording.closed.get()) throw new IOException("识别连接已关闭");
                store.summary(id,"IDLE",meeting.summary(),"");
                store.state(id,"RECORDING","");changed(id);
                recording.send(Map.of("type","ready","protocolVersion",1,"sessionId",recording.epoch,
                    "offsetBytes",recording.bytes,"audioSpec",AudioProtocol.specification()));
            }
        } catch(Exception e) {
            recording.fail(e instanceof ResponseStatusException ? e.getMessage() : "无法开始识别，请检查云端配置和网络。");
            throw e;
        }
    }
    public void audio(String id,WebSocketSession socket,byte[] frame) throws Exception {
        var r=owned(id,socket);
        synchronized(r) {
            byte[] bytes=frame;
            if(r.versioned) {
                var decoded=AudioProtocol.decode(frame);
                if(decoded.sequence()!=r.nextSequence || decoded.offset()!=r.bytes)
                    throw new IllegalArgumentException("音频帧序号或字节偏移不连续，请重新连接后读取 ready.offsetBytes");
                bytes=decoded.pcm();
            }
            if(r.identity.device() && !store.deviceActive(r.identity.deviceId(),r.identity.userId())) {r.fail("设备已解绑，录音已停止");return;}
            if(r.closed.get() || r.finishing.get() || r.cloud==null) throw new IllegalStateException("识别任务尚未就绪");
            if(bytes.length==0 || bytes.length>32000 || bytes.length%2!=0) throw new IllegalArgumentException("音频必须为 16kHz 单声道 PCM16");
            if(r.bytes+bytes.length>settings.maxSeconds()*32000) {r.fail("录音已达到时长上限");return;}
            // Save before forwarding so a cloud failure never discards accepted audio.
            r.file.write(bytes);r.bytes+=bytes.length;r.nextSequence++;r.lastSeen=System.currentTimeMillis();
            if(r.bytes-r.checkpointBytes>=32000) {
                files.checkpoint(r.file);store.audioBytes(id,r.bytes);r.checkpointBytes=r.bytes;
                r.ack();events.publish(id,"progress",Map.of("audioBytes",r.bytes,"durationMs",r.bytes/32));
                var owner=store.get(id).ownerId();if(owner!=null)events.publish("user:"+owner,"changed",Map.of("id",id));
            }
            try {r.cloud.audio(bytes);} catch(Exception e){r.fail("云端音频发送失败，已接收音频已保存。");}
        }
    }
    public void ping(String id,WebSocketSession socket){
        var r=owned(id,socket);r.lastSeen=System.currentTimeMillis();
        r.send(Map.of("type","pong","sessionId",r.epoch));
    }
    public void stop(String id,WebSocketSession socket) {
        var r=owned(id,socket);
        synchronized(r) {
            if(r.closed.get() || !r.finishing.compareAndSet(false,true)) return;
            store.state(id,"FINALIZING","");changed(id);
        }
        workers.submit(()-> {
            try {
                synchronized(r){if(r.closed.get()) return;r.cloud.finish();}
                r.cloud.done.get(25,TimeUnit.SECONDS);
                r.complete();
            } catch(Exception e){r.fail("最后一句确认失败，音频已保存，可重新转录。");}
        });
    }
    public void disconnect(String id,WebSocketSession socket) {
        var r=recordings.get(id);
        if(r!=null && r.socket.getId().equals(socket.getId()) && !r.finishing.get()) r.fail("记录端连接断开，已接收的音频已保存，可继续录音。");
    }
    private Recording owned(String id,WebSocketSession socket) {
        var r=recordings.get(id);
        if(r==null || !r.socket.getId().equals(socket.getId())) throw new IllegalStateException("此连接没有录音权限");
        return r;
    }
    @Scheduled(fixedDelay=15000) public void expire() {
        recordings.values().forEach(r->{if(System.currentTimeMillis()-r.lastSeen>60000) r.fail("记录端长时间无响应，录音已中断。");});
    }
    public void finishInterrupted(String id,String token) {
        authorize(id,token);
        if(!busy.add(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"记录仍在处理中");
        try {
            var m=store.get(id);
            if(!Set.of("INTERRUPTED","FAILED","WAITING").contains(m.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"当前记录不能结束");
            files.finalizeFile(id);store.state(id,"COMPLETED",m.error());changed(id);
        } catch(IOException e){throw new IllegalStateException("保存录音失败",e);} finally{busy.remove(id);}
    }
    public void interruptDevice(String deviceId) {
        recordings.values().forEach(r->{if(deviceId.equals(r.identity.deviceId()))r.fail("设备已解绑，录音已停止；历史记录保留在原用户下");});
    }
    public void reserve(String id) {
        if(!busy.add(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"记录正在处理中");
    }
    public void release(String id) { busy.remove(id); }
    public void authorize(String id,String token) {
        access.meeting(id);
        if(token!=null && !token.isBlank() && !store.authorized(id,token))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"录音凭证错误");
    }
    public void retranscribe(String id,String token) {
        authorize(id,token);
        var config=ai.resolve(Access.current().userId(),"asr");
        if(!config.ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"请先在 AI 设置中配置语音识别 Key");
        if(!busy.add(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"记录正在处理中");
        var m=store.get(id);
        if(m.audioBytes()==0 || !Set.of("COMPLETED","INTERRUPTED","FAILED").contains(m.status()) || m.summaryStatus().equals("RUNNING")) {
            busy.remove(id);throw new ResponseStatusException(HttpStatus.CONFLICT,"请先结束有音频的记录，并等待纪要生成完成");
        }
        store.state(id,"REPROCESSING","");changed(id);
        workers.submit(()-> {
            var result=new LinkedHashMap<String,Store.Segment>();
            String task=UUID.randomUUID().toString();
            try(var cloud=asr.connect(config,m.context(),s->{
                if(s.path("sentence_end").asBoolean() && !s.path("text").asText().isBlank()) {
                    var segment=segment(task,0,s);result.put(segment.id(),segment);
                }
            });var file=new RandomAccessFile(files.path(id).toFile(),"r")) {
                file.seek(44);byte[] buffer=new byte[3200];int length;
                while((length=file.read(buffer))!=-1){cloud.audio(Arrays.copyOf(buffer,length));Thread.sleep(100);}
                cloud.finish();cloud.done.get(25,TimeUnit.SECONDS);
                if(result.isEmpty()) throw new IllegalStateException("云端未返回确认文字，原转录已保留");
                store.replaceSegments(id,new ArrayList<>(result.values()));store.state(id,"COMPLETED","");
            } catch(Exception e){store.state(id,m.status(),"重新转录失败，原转录已保留。请检查云端服务和网络。");}
            finally{busy.remove(id);changed(id);}
        });
    }
    static Store.Segment segment(String task,long offset,JsonNode s) {
        long start=s.path("begin_time").asLong(0), end=s.path("end_time").asLong(start);
        String key=s.has("sentence_id")?s.get("sentence_id").asText():Long.toString(start);
        return new Store.Segment(task+":"+key,offset+start,offset+Math.max(start,end),s.path("text").asText(),s.hasNonNull("speaker_id")?task+":"+s.get("speaker_id").asText():null);
    }
    @PreDestroy public void shutdown(){recordings.values().forEach(r->r.fail("服务停止，录音已保存。"));workers.shutdownNow();}
    private class Recording {
        final String id;
        final String epoch=UUID.randomUUID().toString();
        final WebSocketSession socket;
        final boolean versioned;
        final Identity identity;
        final AtomicBoolean closed=new AtomicBoolean(),finishing=new AtomicBoolean();
        volatile long lastSeen=System.currentTimeMillis();
        CloudAsr.Session cloud;
        RandomAccessFile file;
        long bytes,offset,checkpointBytes,nextSequence;
        Recording(String id,WebSocketSession socket,boolean versioned,Identity identity){this.id=id;this.socket=socket;this.versioned=versioned;this.identity=identity;}
        void ack() {
            if(versioned && nextSequence>0) send(Map.of("type","ack","sessionId",epoch,"sequence",nextSequence-1,"committedBytes",bytes));
        }
        synchronized void result(JsonNode s) {
            if(closed.get()) return;
            var text=s.path("text").asText();
            if(s.path("sentence_end").asBoolean()) {
                if(!text.isBlank()) store.segment(id,segment(epoch,offset,s));drafts.remove(id);
            } else drafts.put(id,text);
            events.publish(id,"snapshot",detail(id));
        }
        void send(String type,String message) {
            var value=new HashMap<String,Object>();
            value.put("type",type);value.put("message",message);value.put("sessionId",epoch);value.put("committedBytes",bytes);
            if(type.equals("error")) value.put("code","RECORDING_INTERRUPTED");
            send(value);
        }
        void send(Map<String,Object> message) {
            try {synchronized(socket){if(socket.isOpen()) socket.sendMessage(new TextMessage(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(message)));}}
            catch(IOException | IllegalStateException ignored) { }
        }
        synchronized void complete(){finish("COMPLETED","");}
        synchronized void fail(String error){finish("INTERRUPTED",error);}
        void finish(String status,String error) {
            if(!closed.compareAndSet(false,true)) return;
            if(cloud!=null) cloud.abort();
            try {
                if(file!=null) {
                    try {files.checkpoint(file);store.audioBytes(id,bytes);ack();}
                    finally {file.close();}
                }
            } catch(Exception e){status="FAILED";error="音频保存失败，请检查磁盘空间与数据库";}
            drafts.remove(id);store.state(id,status,error);
            org.slf4j.LoggerFactory.getLogger(MeetingService.class).info("recording_finished meeting={} session={} status={} audioBytes={}",id,epoch,status,bytes);
            recordings.remove(id,this);busy.remove(id);changed(id);
            send(status.equals("COMPLETED")?"finished":"error",error);
            try{socket.close(CloseStatus.NORMAL);}catch(IOException | IllegalStateException ignored){ }
            if(status.equals("COMPLETED"))completionEvents.publishEvent(new RecordingCompleted(id));
        }
    }
}
