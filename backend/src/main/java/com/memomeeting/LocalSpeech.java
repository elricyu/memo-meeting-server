package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A durable serial queue for self-hosted file transcription. Never invokes cloud AI. */
@Service
public class LocalSpeech {
    public record Input(String url,String token,boolean enabled,Boolean automatic) {}
    public record Config(String url,String token,boolean enabled,boolean automatic) {}
    private final JdbcTemplate db;
    private final SecretBox secrets;
    private final ObjectMapper json;
    private final Store store;
    private final AudioFiles files;
    private final MeetingService meetings;
    @org.springframework.beans.factory.annotation.Value("${LOCAL_SPEECH_URL:}") private String initialUrl="";
    @org.springframework.beans.factory.annotation.Value("${LOCAL_SPEECH_TOKEN:}") private String initialToken="";
    private final AtomicBoolean working=new AtomicBoolean();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public LocalSpeech(JdbcTemplate db,SecretBox secrets,ObjectMapper json,Store store,AudioFiles files,MeetingService meetings){this.db=db;this.secrets=secrets;this.json=json;this.store=store;this.files=files;this.meetings=meetings;}
    public Config config(){return db.query("SELECT * FROM local_speech_config WHERE id=1",(r,n)->new Config(r.getString("url"),secrets.open(0,"local-speech",r.getString("token_cipher")),r.getBoolean("enabled"),r.getBoolean("automatic"))).stream().findFirst().orElse(new Config("","",false,true));}
    public Map<String,Object> view(){var c=config();return Map.of("url",c.url(),"keySet",!c.token().isBlank(),"enabled",c.enabled(),"automatic",c.automatic());}
    static String url(String value){
        try {var uri=URI.create(value==null?"":value.trim());if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException();return uri.toString().replaceAll("/+$","");}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写有效的本地服务 HTTP/HTTPS 地址，不包含账号、查询参数或片段");}
    }
    public synchronized Map<String,Object> save(Input input){var previous=config();var address=url(input.url());var token=input.token()==null || input.token().isBlank()?previous.token():input.token().trim();if(token.length()>4096)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"认证信息过长");db.update("MERGE INTO local_speech_config(id,url,token_cipher,enabled,automatic) KEY(id) VALUES(1,?,?,?,?)",address,secrets.seal(0,"local-speech",token),input.enabled(),input.automatic()==null || input.automatic());return view();}
    private HttpRequest.Builder request(Config c,String suffix){var b=HttpRequest.newBuilder(URI.create(c.url()+suffix)).timeout(Duration.ofSeconds(30));if(!c.token().isBlank())b.header("Authorization","Bearer "+c.token());return b;}
    private com.fasterxml.jackson.databind.JsonNode response(HttpResponse<String> r) throws Exception {if(r.statusCode()<200 || r.statusCode()>=300)throw new java.io.IOException("本地服务返回 HTTP "+r.statusCode());return json.readTree(r.body());}
    public boolean healthy(Config c) throws Exception {return response(http.send(request(c,"/health").GET().build(),HttpResponse.BodyHandlers.ofString())).path("status").asText().equals("ready");}
    public Map<String,Object> test(Input input){var old=config();var candidate=new Config(url(input.url()),input.token()==null || input.token().isBlank()?old.token():input.token(),true,true);try{boolean ready=healthy(candidate);return Map.of("ok",ready,"message",ready?"本地服务已就绪，健康检查通过（未提交录音）":"本地服务尚未就绪");}catch(Exception e){return Map.of("ok",false,"message","无法访问本地服务，请检查地址、SSH 隧道或认证");}}
    @PostConstruct public void recover(){if(!initialUrl.isBlank() && db.queryForObject("SELECT COUNT(*) FROM local_speech_config",Integer.class)==0)save(new Input(initialUrl,initialToken,true,true));db.update("UPDATE local_transcription_jobs SET status='RETRY',next_try=0 WHERE status='RUNNING'");}
    public void retry(String id){var c=config();if(!c.enabled())throw new ResponseStatusException(HttpStatus.CONFLICT,"本地转录服务尚未启用");var m=store.get(id);if(!m.status().equals("COMPLETED") || m.audioBytes()==0 || hasText(id))throw new ResponseStatusException(HttpStatus.CONFLICT,"仅已结束且未转录的录音可以提交本地任务");db.update("MERGE INTO local_transcription_jobs(meeting_id,status,remote_id,remote_url,attempts,next_try,error) KEY(meeting_id) VALUES(?,'QUEUED',?,'',0,0,'')",id,UUID.randomUUID().toString());meetings.changed(id);}
    private boolean hasText(String id){return db.queryForObject("SELECT COUNT(*) FROM segments WHERE meeting_id=?",Long.class,id)>0;}
    @Scheduled(fixedDelay=10000) public void tick(){
        if(!working.compareAndSet(false,true))return;
        try {
            var c=config();if(!c.enabled() || !healthy(c))return;
            if(c.automatic())for(var id:db.queryForList("SELECT m.id FROM meetings m WHERE m.status='COMPLETED' AND m.owner_id IS NOT NULL AND m.audio_bytes>0 AND NOT EXISTS(SELECT 1 FROM segments s WHERE s.meeting_id=m.id) AND NOT EXISTS(SELECT 1 FROM local_transcription_jobs j WHERE j.meeting_id=m.id) ORDER BY m.created_at",String.class)){
                db.update("INSERT INTO local_transcription_jobs(meeting_id,status,remote_id,remote_url,attempts,next_try,error) VALUES(?,'QUEUED',?,'',0,0,'')",id,UUID.randomUUID().toString());meetings.changed(id);
            }
            var rows=db.queryForList("SELECT * FROM local_transcription_jobs WHERE status IN ('QUEUED','RUNNING','RETRY') AND next_try<=? ORDER BY CASE WHEN status='RUNNING' THEN 0 ELSE 1 END,next_try,meeting_id LIMIT 1",System.currentTimeMillis());if(rows.isEmpty())return;
            var job=rows.getFirst();String id=(String)job.get("MEETING_ID"),remote=(String)job.get("REMOTE_ID"),remoteUrl=(String)job.get("REMOTE_URL");
            var m=store.get(id);if(hasText(id) || !m.status().equals("COMPLETED")){state(id,"SKIPPED","已有转录或会议状态改变，保留原记录",0);return;}
            if(!remoteUrl.isBlank() && !remoteUrl.equals(c.url())){state(id,"FAILED","服务地址已更改，请重试任务",0);return;}
            try {
                // A stable request ID makes ambiguous upload responses and restarts safe to retry.
                var check=http.send(request(c,"/v1/jobs/"+remote).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(check.statusCode()==404){
                    var audio=files.path(id);if(Files.size(audio)>128*1024*1024 || m.audioBytes()>3600L*32000){state(id,"FAILED","录音超过本地服务上限（1小时或128MiB）",0);return;}
                    String boundary="memo-"+UUID.randomUUID();
                    var prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"request_id\"\r\n\r\n"+remote+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"meeting.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                    var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),HttpRequest.BodyPublishers.ofFile(audio),HttpRequest.BodyPublishers.ofByteArray(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8)));
                    var uploaded=response(http.send(request(c,"/v1/jobs").header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build(),HttpResponse.BodyHandlers.ofString()));
                    if(!uploaded.path("jobId").asText().equals(remote))throw new java.io.IOException("本地服务不支持稳定 request_id，请更新处理模块");
                    db.update("UPDATE local_transcription_jobs SET remote_url=? WHERE meeting_id=?",c.url(),id);state(id,"RUNNING","",System.currentTimeMillis()+10000);return;
                }
                var result=response(check);var status=result.path("status").asText();
                if(status.equals("queued") || status.equals("running")){state(id,"RUNNING","",System.currentTimeMillis()+10000);return;}
                if(status.equals("failed")){db.update("UPDATE local_transcription_jobs SET remote_id=?,remote_url='' WHERE meeting_id=?",UUID.randomUUID().toString(),id);throw new java.io.IOException("任务失败，重新排队");}
                if(!status.equals("completed"))throw new java.io.IOException("未知任务状态");
                var segments=new ArrayList<Store.Segment>();var content=result.path("result").path("segments");if(!content.isArray())throw new java.io.IOException("本地服务结果无效");
                for(var s:content){String text=s.path("text").asText().trim();long start=s.path("startMs").asLong(-1),end=s.path("endMs").asLong(-1);if(start<0 || end<start || end>m.audioBytes()/32+1000)throw new java.io.IOException("时间戳无效");if(!text.isBlank())segments.add(new Store.Segment(remote+":"+segments.size(),start,end,text,s.hasNonNull("speakerId")?remote+":"+s.path("speakerId").asText():null));}
                if(segments.isEmpty()){state(id,"EMPTY","未检出语音，可手动重试或选择云端转录",0);return;}
                meetings.reserve(id);
                try {if(hasText(id) || !store.get(id).status().equals("COMPLETED")){state(id,"SKIPPED","保留已有记录",0);return;}store.replaceSegments(id,segments);state(id,"DONE","",0);}finally{meetings.release(id);}
            } catch(Exception e){int attempts=((Number)job.get("ATTEMPTS")).intValue()+1;db.update("UPDATE local_transcription_jobs SET attempts=? WHERE meeting_id=?",attempts,id);state(id,attempts>=8?"FAILED":"RETRY","本地任务暂未完成，检查服务或稍后重试",System.currentTimeMillis()+Math.min(300000,10000L*(1L<<Math.min(attempts,5))));}
        } catch(Exception e){org.slf4j.LoggerFactory.getLogger(LocalSpeech.class).debug("Local transcription unavailable: {}",e.getClass().getSimpleName());}
        finally {working.set(false);}
    }
    private void state(String id,String status,String error,long next){org.slf4j.LoggerFactory.getLogger(LocalSpeech.class).info("local_transcription_status meeting={} status={}",id,status);db.update("UPDATE local_transcription_jobs SET status=?,error=?,next_try=? WHERE meeting_id=?",status,error,next,id);meetings.changed(id);}
}
