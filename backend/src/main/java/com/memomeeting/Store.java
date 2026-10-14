package com.memomeeting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Repository
public class Store {
    public record Meeting(String id, String title, String context, String status, String createdAt,
                          String updatedAt, long audioBytes, String error, String summary,
                          String summaryStatus, String summaryError, String source, Long ownerId, String deviceId) {
        public long durationMs() { return audioBytes / 32; }
    }
    public record Segment(String id, long startMs, long endMs, String text,String speakerId) {
        public Segment(String id,long startMs,long endMs,String text){this(id,startMs,endMs,text,null);}
    }
    public record Speaker(String id,String name) {}
    private final JdbcTemplate db;
    public Store(JdbcTemplate db) { this.db = db; }
    public List<Meeting> list() {
        return db.query("SELECT * FROM meetings ORDER BY created_at DESC LIMIT 500", (r, n) ->
            new Meeting(r.getString("id"), r.getString("title"), r.getString("context"),
                r.getString("status"), r.getString("created_at"), r.getString("updated_at"),
                r.getLong("audio_bytes"), r.getString("error"), r.getString("summary"),
                r.getString("summary_status"), r.getString("summary_error"), r.getString("source"), r.getObject("owner_id",Long.class), r.getString("device_id")));
    }
    public Meeting get(String id) {
        return db.query("SELECT * FROM meetings WHERE id=?", (r, n) ->
            new Meeting(r.getString("id"), r.getString("title"), r.getString("context"),
                r.getString("status"), r.getString("created_at"), r.getString("updated_at"),
                r.getLong("audio_bytes"), r.getString("error"), r.getString("summary"),
                r.getString("summary_status"), r.getString("summary_error"), r.getString("source"), r.getObject("owner_id",Long.class), r.getString("device_id")), id)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在"));
    }
    public Meeting create(String title, String context, String token) {
        String id=UUID.randomUUID().toString(), now=Instant.now().toString();
        db.update("INSERT INTO meetings(id,title,original_title,context,recorder_hash,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            id,title,title,context,hash(token),"WAITING",now,now);
        return get(id);
    }
    public synchronized Meeting createIdempotent(String title,String context,String token,String source,String requestId) {
        if(requestId!=null) {
            var ids=db.queryForList("SELECT id FROM meetings WHERE client_request_id=?",String.class,requestId);
            if(!ids.isEmpty()) {
                var existing=get(ids.getFirst());
                String originalTitle=db.queryForObject("SELECT COALESCE(original_title,title) FROM meetings WHERE id=?",String.class,existing.id());
                if(!authorized(existing.id(),token) || !originalTitle.equals(title) || !existing.context().equals(context) || !existing.source().equals(source))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"同一 clientRequestId 不能创建不同内容的记录");
                return existing;
            }
        }
        String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        db.update("INSERT INTO meetings(id,title,original_title,context,recorder_hash,status,created_at,updated_at,source,client_request_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            id,title,title,context,hash(token),"WAITING",now,now,source,requestId);
        return get(id);
    }
    public List<Meeting> listFor(Identity identity) {
        return db.query("SELECT id FROM meetings WHERE owner_id=?"+(identity.device()?" AND device_id=?":"")+" ORDER BY created_at DESC LIMIT 500",
            (r,n)->r.getString(1),identity.device()?new Object[]{identity.userId(),identity.deviceId()}:new Object[]{identity.userId()}).stream().map(this::get).toList();
    }
    public record ListedMeeting(@com.fasterxml.jackson.annotation.JsonUnwrapped Meeting meeting,long transcriptCount) {}
    public List<ListedMeeting> listWithProgressFor(Identity identity) {
        var meetings=listFor(identity);
        if(meetings.isEmpty())return List.of();
        var counts=new HashMap<String,Long>();
        String placeholders=String.join(",",Collections.nCopies(meetings.size(),"?"));
        db.query("SELECT meeting_id,COUNT(*) AS total FROM segments WHERE excluded=FALSE AND TRIM(content)<>'' AND meeting_id IN ("+placeholders+") GROUP BY meeting_id",
            (org.springframework.jdbc.core.RowCallbackHandler)r->counts.put(r.getString("meeting_id"),r.getLong("total")),meetings.stream().map(Meeting::id).toArray());
        return meetings.stream().map(m->new ListedMeeting(m,counts.getOrDefault(m.id(),0L))).toList();
    }
    public synchronized Meeting createOwned(String title,String context,String token,String source,String requestId,Identity identity) {
        if(requestId!=null) {
            var ids=db.queryForList("SELECT id FROM meetings WHERE client_request_id=?",String.class,requestId);
            if(!ids.isEmpty()) {
                var m=get(ids.getFirst());
                if(m.ownerId()==null || m.ownerId()!=identity.userId() || !Objects.equals(m.deviceId(),identity.deviceId()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"创建请求 ID 已被使用");
            }
        }
        var meeting=createIdempotent(title,context,token,source,requestId);
        if(meeting.ownerId()==null) db.update("UPDATE meetings SET owner_id=?,device_id=? WHERE id=?",identity.userId(),identity.deviceId(),meeting.id());
        return get(meeting.id());
    }
    public boolean deviceActive(String id,long owner) {return db.queryForObject("SELECT COUNT(*) FROM devices WHERE id=? AND owner_id=? AND status='BOUND'",Long.class,id,owner)==1;}
    public List<String> allIds() { return db.queryForList("SELECT id FROM meetings",String.class); }
    public boolean authorized(String id, String token) {
        if (token == null || token.isBlank()) return false;
        var values=db.queryForList("SELECT recorder_hash FROM meetings WHERE id=?",String.class,id);
        return !values.isEmpty() && MessageDigest.isEqual(values.getFirst().getBytes(StandardCharsets.UTF_8),hash(token).getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public void state(String id, String status, String error) {
        db.update("UPDATE meetings SET status=?,error=?,updated_at=? WHERE id=?",status,clip(error),Instant.now().toString(),id);
    }
    public void audioBytes(String id, long bytes) {
        db.update("UPDATE meetings SET audio_bytes=?,updated_at=? WHERE id=?", bytes,Instant.now().toString(),id);
    }
    public List<Segment> segments(String id) {
        return db.query("SELECT * FROM segments WHERE meeting_id=? AND excluded=FALSE ORDER BY start_ms,segment_id",
            (r,n)->new Segment(r.getString("segment_id"),r.getLong("start_ms"),r.getLong("end_ms"),r.getString("content"),r.getString("speaker_id")),id);
    }
    public synchronized void segment(String id, Segment segment) {
        if(segment.speakerId()!=null && db.queryForObject("SELECT COUNT(*) FROM speakers WHERE meeting_id=? AND speaker_id=?",Long.class,id,segment.speakerId())==0) {
            long number=db.queryForObject("SELECT COUNT(*) FROM speakers WHERE meeting_id=?",Long.class,id)+1;
            db.update("INSERT INTO speakers(meeting_id,speaker_id,name) VALUES(?,?,?)",id,segment.speakerId(),"说话人 "+number);
        }
        db.update("MERGE INTO segments(meeting_id,segment_id,start_ms,end_ms,content,speaker_id) KEY(meeting_id,segment_id) VALUES(?,?,?,?,?,?)",
            id,segment.id(),segment.startMs(),segment.endMs(),segment.text(),segment.speakerId());
    }
    public List<Segment> exclusionCandidates(String id,List<String> ids) {
        var result=new ArrayList<Segment>();
        for(String key:ids){
            var rows=db.query("SELECT * FROM segments WHERE meeting_id=? AND segment_id=?",(r,n)->new Segment(r.getString("segment_id"),r.getLong("start_ms"),r.getLong("end_ms"),r.getString("content"),r.getString("speaker_id")),id,key);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"排除片段不存在");
            result.add(rows.getFirst());
        }
        return result;
    }
    @Transactional public void excludeSegments(String id,List<String> ids,String reason) {
        ids.forEach(key->db.update("UPDATE segments SET excluded=TRUE,exclusion_reason=? WHERE meeting_id=? AND segment_id=?",reason,id,key));
        db.update("UPDATE meetings SET summary='',summary_status='IDLE',summary_error='' WHERE id=?",id);
    }
    public List<Speaker> speakers(String id){return db.query("SELECT speaker_id,name FROM speakers WHERE meeting_id=? ORDER BY (SELECT MIN(start_ms) FROM segments WHERE meeting_id=? AND speaker_id=speakers.speaker_id)",(r,n)->new Speaker(r.getString(1),r.getString(2)),id,id);}
    public void renameSpeaker(String id,String speaker,String name){
        if(db.update("UPDATE speakers SET name=? WHERE meeting_id=? AND speaker_id=?",name,id,speaker)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"说话人不存在");
        db.update("UPDATE meetings SET summary='',summary_status='IDLE',summary_error='' WHERE id=?",id);
    }
    @Transactional
    public void replaceSegments(String id, List<Segment> segments) {
        db.update("DELETE FROM segments WHERE meeting_id=?",id);
        db.update("DELETE FROM speakers WHERE meeting_id=?",id);
        segments.forEach(s->segment(id,s));
        db.update("UPDATE meetings SET summary='',summary_status='IDLE',summary_error='' WHERE id=?",id);
    }
    public void summary(String id, String status, String content, String error) {
        db.update("UPDATE meetings SET summary_status=?,summary=?,summary_error=?,updated_at=? WHERE id=?",
            status,content,clip(error),Instant.now().toString(),id);
    }
    public void completeSummary(String id,String title,String content) {
        // One update makes the new title and successful summary visible together.
        db.update("UPDATE meetings SET title=?,summary=?,summary_status='DONE',summary_error='',updated_at=? WHERE id=?",title,content,Instant.now().toString(),id);
    }
    public void recover() {
        db.update("UPDATE meetings SET status='INTERRUPTED',error='服务重启，录音已中断。可继续录音或重新转录已保存音频。' WHERE status IN ('CONNECTING','RECORDING','FINALIZING','REPROCESSING')");
        db.update("UPDATE meetings SET summary_status='FAILED',summary_error='服务重启，请重新生成纪要。' WHERE summary_status='RUNNING'");
    }
    private String clip(String value) { return value == null ? "" : value.substring(0,Math.min(600,value.length())); }
}
