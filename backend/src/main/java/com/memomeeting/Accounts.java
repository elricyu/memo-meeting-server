package com.memomeeting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DuplicateKeyException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class Accounts {
    public record User(long id,String username,String displayName,String role) {}
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    private final String dummyHash=passwords.encode(UUID.randomUUID().toString());
    public Accounts(JdbcTemplate db){this.db=db;}
    @org.springframework.beans.factory.annotation.Value("${memo.admin-username:}") private String bootstrapAdmin;
    @jakarta.annotation.PostConstruct public void initializeAdmin(){
        if(bootstrapAdmin==null || bootstrapAdmin.isBlank())return;
        if(!bootstrapAdmin.matches("[A-Za-z0-9_]{3,40}"))throw new IllegalStateException("管理员用户名格式无效");
        int count=db.update("UPDATE users SET role='ADMIN' WHERE username=?",bootstrapAdmin.toLowerCase(Locale.ROOT));
        if(count!=1)throw new IllegalStateException("指定管理员账号尚未注册，请先创建账号");
        org.slf4j.LoggerFactory.getLogger(Accounts.class).info("administrator_granted username={}",bootstrapAdmin);
    }
    public List<Long> userIds(){return db.queryForList("SELECT id FROM users",Long.class);}
    public boolean admin(long id){return "ADMIN".equals(get(id).role());}
    public User register(String username,String displayName,String password){
        if(password.getBytes(StandardCharsets.UTF_8).length>72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"密码 UTF-8 编码不能超过 72 字节");
        String normalized=username.toLowerCase(Locale.ROOT);
        try{db.update("INSERT INTO users(username,display_name,password_hash,created_at) VALUES(?,?,?,?)",normalized,displayName.trim(),passwords.encode(password),Instant.now().toString());}
        catch(DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"用户名已存在");}
        return byUsername(normalized);
    }
    public User login(String username,String password){
        var rows=db.queryForList("SELECT * FROM users WHERE username=?",username.toLowerCase(Locale.ROOT));
        String hash=rows.isEmpty()?dummyHash:(String)rows.getFirst().get("PASSWORD_HASH");
        boolean valid=password.getBytes(StandardCharsets.UTF_8).length<=72 && passwords.matches(password,hash);
        if(!valid || rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户名或密码错误");
        return byUsername(username.toLowerCase(Locale.ROOT));
    }
    public User byUsername(String username){return db.query("SELECT id,username,display_name,role FROM users WHERE username=?",(r,n)->new User(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)),username).getFirst();}
    public User get(long id){return db.query("SELECT id,username,display_name,role FROM users WHERE id=?",(r,n)->new User(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户不存在"));}
}
