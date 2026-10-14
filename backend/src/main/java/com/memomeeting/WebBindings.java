package com.memomeeting;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/** The user initiates binding on HTTPS. Device claims are inert until the same user confirms. */
@Service
public class WebBindings {
    public record Request(String id,String qrToken,String expiresAt,String status,Devices.Device device) {}
    private final JdbcTemplate db;
    private final Devices devices;
    private final SecureRandom random=new SecureRandom();
    public WebBindings(JdbcTemplate db,Devices devices){this.db=db;this.devices=devices;}
    @jakarta.annotation.PostConstruct public void initQrSecrets(){db.execute("CREATE TABLE IF NOT EXISTS qr_binding_secrets(binding_id VARCHAR(36) PRIMARY KEY REFERENCES web_bindings(id), token_hash VARCHAR(64) NOT NULL UNIQUE)");}
    @Transactional public synchronized Request create(long user){
        expire();
        for(var row:db.queryForList("SELECT id FROM web_bindings WHERE user_id=? AND status IN ('WAITING','CLAIMED')",user))cancel((String)row.get("ID"),user);
        String id=UUID.randomUUID().toString(),code;
        do{code=String.format(Locale.ROOT,"%08d",random.nextInt(100000000));}while(db.queryForObject("SELECT COUNT(*) FROM web_bindings WHERE code=?",Long.class,code)>0);
        db.update("INSERT INTO web_bindings(id,user_id,code,expires_at,status) VALUES(?,?,?,?,'WAITING')",id,user,code,System.currentTimeMillis()+300000);
        byte[] tokenBytes=new byte[32];random.nextBytes(tokenBytes);String token=HexFormat.of().formatHex(tokenBytes);
        db.update("INSERT INTO qr_binding_secrets(binding_id,token_hash) VALUES(?,?)",id,Devices.hash(token));
        var result=read(id,user);return new Request(id,token,result.expiresAt(),result.status(),result.device());
    }
    public synchronized Request read(String id,long user){
        expire();var row=owned(id,user);String device=(String)row.get("DEVICE_ID");
        return new Request(id,null,Instant.ofEpochMilli(((Number)row.get("EXPIRES_AT")).longValue()).toString(),(String)row.get("STATUS"),device==null?null:db.query("SELECT * FROM devices WHERE id=?",(r,n)->new Devices.Device(r.getString("id"),r.getString("client_id"),r.getString("mac"),r.getString("display_name"),r.getString("platform"),r.getString("status"),r.getString("created_at")),device).getFirst());
    }
    @Transactional public synchronized Object claim(String requestId,String token,DeviceApi.Pair input){
        expire();var rows=db.queryForList("SELECT w.* FROM web_bindings w JOIN qr_binding_secrets q ON q.binding_id=w.id WHERE w.id=? AND q.token_hash=? AND w.status IN ('WAITING','CLAIMED') AND w.expires_at>?",requestId,Devices.hash(token),System.currentTimeMillis());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"绑定二维码无效或已过期，请重新在网页发起绑定");
        var row=rows.getFirst();
        if(row.get("STATUS").equals("CLAIMED") && !db.queryForObject("SELECT request_id FROM devices WHERE id=?",String.class,row.get("DEVICE_ID")).equals(input.clientRequestId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"已有眼镜申请，请先在网页确认或取消");
        @SuppressWarnings("unchecked") var pairing=(Map<String,Object>)devices.initiate(input.clientId().trim(),input.mac().toUpperCase(Locale.ROOT),input.displayName().trim(),input.platform().trim(),input.deviceToken(),input.clientRequestId());
        db.update("UPDATE web_bindings SET status='CLAIMED',device_id=?,pairing_id=? WHERE id=?",pairing.get("deviceId"),pairing.get("pairingId"),row.get("ID"));
        // Do not disclose the device-created pairing code; only the initiating user may confirm.
        return Map.of("pairingId",pairing.get("pairingId"),"deviceId",pairing.get("deviceId"),"expiresAt",Instant.ofEpochMilli(((Number)row.get("EXPIRES_AT")).longValue()).toString(),"status","PENDING","pollIntervalSeconds",3);
    }
    @Transactional public synchronized Object confirm(String id,long user){
        expire();var row=owned(id,user);
        if(!row.get("STATUS").equals("CLAIMED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"没有可确认的设备申请，或此绑定请求已结束");
        String code=db.queryForObject("SELECT code FROM pairings WHERE id=?",String.class,row.get("PAIRING_ID"));
        var device=devices.bind(code,user);db.update("UPDATE web_bindings SET status='BOUND' WHERE id=?",id);return device;
    }
    public Object recover(String requestId,String token){
        expire();var rows=db.queryForList("SELECT p.id FROM devices d JOIN pairings p ON p.device_id=d.id WHERE d.request_id=? AND d.token_hash=?",requestId,Devices.hash(token));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"设备申请不存在");
        return Map.of("pairingId",rows.getFirst().get("ID"));
    }
    /** Anonymous hardware check discloses no owner or credential, and grants no access. */
    public synchronized Object check(String clientId,String mac,String token){
        expire();
        var rows=db.queryForList("SELECT d.client_id,d.mac,d.token_hash,d.status FROM devices d LEFT JOIN pairings p ON p.device_id=d.id WHERE (d.client_id=? OR d.mac=?) AND (d.status='BOUND' OR (d.status='PENDING' AND p.status='PENDING' AND p.expires_at>?))",clientId,mac,System.currentTimeMillis());
        for(var row:rows){
            if(token!=null && clientId.equals(row.get("CLIENT_ID")) && mac.equals(row.get("MAC")) && Devices.hash(token).equals(row.get("TOKEN_HASH"))){
                String state=(String)row.get("STATUS");
                return Map.of("state",state,"message",state.equals("BOUND")?"已绑定，可打开会议记录":"请在网页绑定眼镜并输入验证码");
            }
        }
        if(!rows.isEmpty())return Map.of("state","REBIND_REQUIRED","message","当前凭证不可用，请在原账号解绑旧设备或取消待确认申请，再打开会议记录获取验证码");
        return Map.of("state","UNBOUND","message","当前设备未绑定，请在网页输入眼镜播报的验证码");
    }
    @Transactional public synchronized void cancel(String id,long user){
        var row=owned(id,user);
        if(Set.of("WAITING","CLAIMED").contains(row.get("STATUS"))) {release(row);db.update("UPDATE web_bindings SET status='CANCELLED' WHERE id=?",id);}
    }
    private Map<String,Object> owned(String id,long user){var rows=db.queryForList("SELECT * FROM web_bindings WHERE id=? AND user_id=?",id,user);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"绑定请求不存在");return rows.getFirst();}
    private void release(Map<String,Object> row){if(row.get("DEVICE_ID")!=null){db.update("UPDATE devices SET status='REVOKED' WHERE id=? AND status='PENDING'",row.get("DEVICE_ID"));db.update("UPDATE pairings SET status='EXPIRED' WHERE id=? AND status='PENDING'",row.get("PAIRING_ID"));}}
    @Scheduled(fixedDelay=15000) public synchronized void expire(){
        for(var row:db.queryForList("SELECT * FROM web_bindings WHERE status IN ('WAITING','CLAIMED') AND expires_at<?",System.currentTimeMillis())){release(row);db.update("UPDATE web_bindings SET status='EXPIRED' WHERE id=?",row.get("ID"));}
    }
}
