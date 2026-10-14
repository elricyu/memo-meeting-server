package com.memomeeting;

import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Global AI credentials are encrypted at rest; the master key stays outside the database. */
@Component
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class SecretBox {
    private final byte[] master;
    private final SecureRandom random=new SecureRandom();
    public SecretBox(Settings settings, org.springframework.jdbc.core.JdbcTemplate db) throws Exception {
        var path=Path.of(settings.dataDir(),"ai-master.key");Files.createDirectories(path.getParent());
        if(!Files.exists(path)) {
            var encrypted=db.queryForObject("SELECT (SELECT COUNT(*) FROM global_ai_configs WHERE key_cipher<>'')+(SELECT COUNT(*) FROM ai_configs WHERE key_cipher<>'')+(SELECT COUNT(*) FROM local_speech_config WHERE token_cipher<>'')",Long.class);
            if(encrypted!=null && encrypted>0)throw new IllegalStateException("已有 AI 凭证但缺少 ai-master.key，请恢复原始文件");
            byte[] key=new byte[32];random.nextBytes(key);
            try {
                if(Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))
                    Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                else Files.createFile(path);
                Files.write(path,key,StandardOpenOption.WRITE);
            } catch(FileAlreadyExistsException ignored) { }
        }
        master=Files.readAllBytes(path);
        if(master.length!=32)throw new IllegalStateException("AI 凭证加密文件无效，请恢复原始 ai-master.key");
    }
    public String seal(long user,String type,String secret) {
        try {
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD((user+":"+type).getBytes(StandardCharsets.UTF_8));
            byte[] value=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)), result=new byte[12+value.length];
            System.arraycopy(nonce,0,result,0,12);System.arraycopy(value,0,result,12,value.length);
            return Base64.getEncoder().encodeToString(result);
        } catch(Exception e){throw new IllegalStateException("无法加密 AI 凭证");}
    }
    public String open(long user,String type,String value) {
        if(value==null || value.isBlank())return "";
        try {
            var bytes=Base64.getDecoder().decode(value);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,bytes,0,12));
            cipher.updateAAD((user+":"+type).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
        } catch(Exception e){throw new IllegalStateException("无法解密 AI 凭证，请检查 ai-master.key 或重新填写 Key");}
    }
}
