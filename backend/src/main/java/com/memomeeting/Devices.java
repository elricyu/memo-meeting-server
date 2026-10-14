package com.memomeeting;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class Devices {
    public record Device(String id,String clientId,String mac,String displayName,String platform,String status,String createdAt) {}
    private final JdbcTemplate db;
    private final MeetingService meetings;
    private final Events events;
    private final Accounts accounts;
    private final SecureRandom random=new SecureRandom();
    private void lockPairings(){db.queryForObject("SELECT id FROM pairing_lock WHERE id=1 FOR UPDATE",Integer.class);}
    private String nextCode(){
        long now=System.currentTimeMillis();
        // Keep recently expired/consumed codes reserved for ten minutes against late input.
        db.update("UPDATE pairings SET code=NULL WHERE expires_at<=? AND (status<>'PENDING' OR expires_at<=?)",now-600000,now);
        db.update("DELETE FROM pairing_code_reservations WHERE available_after<=?",now);
        int start=random.nextInt(10000);
        var used=new HashSet<>(db.queryForList("SELECT code FROM pairings WHERE code IS NOT NULL",String.class));
        used.addAll(db.queryForList("SELECT code FROM pairing_code_reservations",String.class));
        for(int i=0;i<10000;i++){
            String code=String.format(Locale.ROOT,"%04d",(start+i)%10000);
            if(!used.contains(code)){db.update("INSERT INTO pairing_code_reservations(code,available_after) VALUES(?,?)",code,now+900000);return code;}
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"绑定申请较多，请稍后重试");
    }
    public Devices(JdbcTemplate db,MeetingService meetings,Events events,Accounts accounts){this.db=db;this.meetings=meetings;this.events=events;this.accounts=accounts;}
    static String hash(String secret){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public Identity authenticate(String token){
        if(token.length()<32 || token.length()>256)return null;
        var rows=db.query("SELECT d.id,u.id,u.username,u.display_name FROM devices d JOIN users u ON u.id=d.owner_id WHERE d.token_hash=? AND d.status='BOUND'",(r,n)->new Identity(r.getLong(2),r.getString(3),r.getString(4),r.getString(1)),hash(token));
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional public synchronized Object initiate(String clientId,String mac,String displayName,String platform,String token,String requestId){
        lockPairings();
        var previous=db.queryForList("SELECT * FROM devices WHERE request_id=?",requestId);
        if(!previous.isEmpty()){
            var row=previous.getFirst();
            if(!row.get("TOKEN_HASH").equals(hash(token)) || !row.get("CLIENT_ID").equals(clientId) || !row.get("MAC").equals(mac) || !row.get("PLATFORM").equals(platform) || !row.get("DISPLAY_NAME").equals(displayName))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"绑定请求 ID 已被其他请求使用");
            String device=(String)row.get("ID");
            var pairing=db.queryForList("SELECT * FROM pairings WHERE device_id=?",device).getFirst();
            if(!"BOUND".equals(row.get("STATUS")) && Set.of("PENDING","EXPIRED").contains(pairing.get("STATUS"))
                && ((Number)pairing.get("EXPIRES_AT")).longValue()<=System.currentTimeMillis()){
                if(db.queryForObject("SELECT COUNT(*) FROM devices WHERE id<>? AND (client_id=? OR mac=?) AND status IN ('BOUND','PENDING')",Long.class,device,clientId,mac)>0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"此硬件已有新的绑定申请");
                if(db.queryForObject("SELECT COUNT(*) FROM web_bindings WHERE device_id=?",Long.class,device)>0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"旧版二维码申请已结束，请重新发起绑定");
                String code=nextCode();
                db.update("UPDATE devices SET status='PENDING' WHERE id=?",device);
                db.update("UPDATE pairings SET code=?,status='PENDING',expires_at=? WHERE device_id=?",code,System.currentTimeMillis()+300000,device);
            }
            return pairingFor(device);
        }
        long now=System.currentTimeMillis();
        db.update("UPDATE pairings SET status='EXPIRED' WHERE status='PENDING' AND expires_at<=?",now);
        db.update("UPDATE devices SET status='REVOKED' WHERE status='PENDING' AND id IN (SELECT device_id FROM pairings WHERE status='EXPIRED')");
        if(db.queryForObject("SELECT COUNT(*) FROM devices WHERE (client_id=? OR mac=?) AND status IN ('BOUND','PENDING')",Long.class,clientId,mac)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"此硬件已有绑定或有效绑定请求，请先解绑或等待绑定码过期");
        if(db.queryForObject("SELECT COUNT(*) FROM devices WHERE token_hash=?",Long.class,hash(token))>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"设备凭证不可复用，请生成新随机凭证");
        String device=UUID.randomUUID().toString(),pairing=UUID.randomUUID().toString(),instant=Instant.now().toString(),code;
        code=nextCode();
        db.update("INSERT INTO devices(id,hardware_id,client_id,mac,display_name,platform,token_hash,status,request_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PENDING',?,?,?)",device,clientId,clientId,mac,displayName,platform,hash(token),requestId,instant,instant);
        db.update("INSERT INTO pairings(id,device_id,code,expires_at,status) VALUES(?,?,?,?,'PENDING')",pairing,device,code,now+300000);
        return pairingFor(device);
    }
    private Object pairingFor(String device){
        var rows=db.queryForList("SELECT * FROM pairings WHERE device_id=?",device);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"此请求不可重试，请生成新绑定请求");
        var row=rows.getFirst();return Map.of("pairingId",row.get("ID"),"deviceId",device,"pairingCode",Objects.toString(row.get("CODE"),""),"expiresAt",Instant.ofEpochMilli(((Number)row.get("EXPIRES_AT")).longValue()).toString(),"pollIntervalSeconds",3);
    }
    public Object poll(String pairing,String token){
        var rows=db.queryForList("SELECT p.*,d.owner_id,d.token_hash,d.status AS device_status,EXISTS(SELECT 1 FROM web_bindings w WHERE w.device_id=d.id) AS qr_managed FROM pairings p JOIN devices d ON d.id=p.device_id WHERE p.id=?",pairing);
        if(rows.isEmpty() || token==null || !hash(token).equals(rows.getFirst().get("TOKEN_HASH")))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"绑定请求不存在");
        var row=rows.getFirst();String status=(String)row.get("STATUS");
        if(row.get("DEVICE_STATUS").equals("REVOKED"))status="EXPIRED".equals(status) && Boolean.FALSE.equals(row.get("QR_MANAGED"))?"EXPIRED":"REVOKED";
        else if(status.equals("PENDING") && ((Number)row.get("EXPIRES_AT")).longValue()<=System.currentTimeMillis())status="EXPIRED";
        var result=new HashMap<String,Object>();result.put("status",status);result.put("deviceId",row.get("DEVICE_ID"));
        if(status.equals("BOUND")){var user=accounts.get(((Number)row.get("OWNER_ID")).longValue());result.put("owner",Map.of("id",user.id(),"displayName",user.displayName()));}
        return result;
    }
    public Device preview(String code){return candidate(code);}
    private Device candidate(String input){
        String code=input.replace("-","").replace(" ","").toUpperCase(Locale.ROOT);
        var rows=db.query("SELECT d.* FROM pairings p JOIN devices d ON d.id=p.device_id WHERE p.code=? AND p.status='PENDING' AND d.status='PENDING' AND p.expires_at>?",(r,n)->new Device(r.getString("id"),r.getString("client_id"),r.getString("mac"),r.getString("display_name"),r.getString("platform"),r.getString("status"),r.getString("created_at")),code,System.currentTimeMillis());
        return rows.stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"验证码无效、已使用或已过期，请确认眼镜绑定状态或填写最新验证码"));
    }
    @Transactional public synchronized Device bind(String code,long userId){
        lockPairings();
        var device=candidate(code);String now=Instant.now().toString();
        if(db.queryForObject("SELECT COUNT(*) FROM web_bindings WHERE device_id=? AND (user_id<>? OR status<>'CLAIMED' OR expires_at<=?)",Long.class,device.id(),userId,System.currentTimeMillis())>0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"此设备申请不属于当前账号或已过期");
        int changed=db.update("UPDATE devices SET status='BOUND',owner_id=?,updated_at=? WHERE id=? AND status='PENDING'",userId,now,device.id());
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"此绑定码已被使用");
        int consumed=db.update("UPDATE pairings SET status='BOUND',expires_at=? WHERE device_id=? AND status='PENDING' AND expires_at>?",System.currentTimeMillis(),device.id(),System.currentTimeMillis());
        if(consumed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"验证码已使用或已过期");
        return new Device(device.id(),device.clientId(),device.mac(),device.displayName(),device.platform(),"BOUND",device.createdAt());
    }
    public List<Device> list(long userId){return db.query("SELECT * FROM devices WHERE owner_id=? AND status='BOUND' ORDER BY created_at DESC",(r,n)->new Device(r.getString("id"),r.getString("client_id"),r.getString("mac"),r.getString("display_name"),r.getString("platform"),r.getString("status"),r.getString("created_at")),userId);}
    public synchronized void unbind(String id,long userId){
        int changed=db.update("UPDATE devices SET status='REVOKED',updated_at=? WHERE id=? AND owner_id=? AND status='BOUND'",Instant.now().toString(),id,userId);
        if(changed!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"设备不存在或已解绑");
        events.closeActor("device:"+id);meetings.interruptDevice(id);
    }
}
