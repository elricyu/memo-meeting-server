package com.memomeeting;

import com.fasterxml.jackson.databind.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/** Tencent realtime V2: single-channel PCM and server-side voice clustering. */
final class TencentAsr {
    /** Only controlled messages leave the server; provider payloads may contain credentials. */
    static final class Rejected extends IllegalStateException {
        final int providerCode;
        Rejected(int code){super("腾讯语音识别错误码 "+code);providerCode=code;}
        String resultCode(){return switch(providerCode){
            case 4001 -> "TENCENT_INVALID_PARAMETERS";
            case 4002 -> "TENCENT_AUTH_FAILED";
            case 4003 -> "TENCENT_SERVICE_NOT_ENABLED";
            case 4004 -> "TENCENT_QUOTA_EXHAUSTED";
            case 4005 -> "TENCENT_PAYMENT_REQUIRED";
            case 4006 -> "TENCENT_CONCURRENCY_LIMIT";
            case 6001 -> "TENCENT_REGION_RESTRICTED";
            default -> "TENCENT_REJECTED";
        };}
        String userMessage(){return switch(providerCode){
            case 4001 -> "腾讯云拒绝接口参数，请检查模型、AppID 和热词表设置";
            case 4002 -> "腾讯云签名鉴权失败，请检查 AppID 与这对 SecretId、SecretKey 是否匹配且已启用";
            case 4003 -> "此 AppID 尚未开通腾讯云语音识别服务，请到腾讯云 ASR 控制台开通";
            case 4004 -> "腾讯云语音识别资源额度不足，请购买大模型 2.0 对应资源包或开通后付费";
            case 4005 -> "腾讯云账号欠费，语音识别已停服，请在腾讯云控制台处理欠费";
            case 4006 -> "腾讯云语音识别并发数已达上限，请稍后重试";
            case 4007 -> "腾讯云拒绝音频格式，请检查 PCM 编码和采样率";
            case 4008 -> "腾讯云等待音频超时，请检查音频上传";
            case 6001 -> "腾讯云拒绝当前调用地域，请检查服务器出口是否经境外代理";
            default -> "腾讯云拒绝识别请求，请按错误码在腾讯云文档排查";
        }+"（腾讯错误码 "+providerCode+"）";}
    }
    record Credentials(String appId,String secretId,String secretKey) {
        @Override public String toString(){return "Tencent credentials[redacted]";}
        String encoded(){return appId+":"+secretId+":"+secretKey;}
    }
    static Credentials credentials(String value){
        String[] parts=value.split(":",-1);
        if(parts.length!=3)throw new IllegalStateException("腾讯云凭证格式无效，请在管理员设置重新填写");
        return new Credentials(parts[0],parts[1],parts[2]);
    }
    static String signedUrl(AiConfigs.Config config,String voiceId) throws Exception {
        return signedUrl(config,voiceId,Instant.now().getEpochSecond(),new SecureRandom().nextInt(Integer.MAX_VALUE-1)+1);
    }
    static String signedUrl(AiConfigs.Config config,String voiceId,long timestamp,int nonce) throws Exception {
        var credentials=credentials(config.apiKey());
        String root=config.url()+"/"+credentials.appId();
        var params=new TreeMap<String,String>();
        params.put("engine_model_type",config.model());params.put("expired",Long.toString(timestamp+3600));
        params.put("needvad","1");params.put("nonce",Integer.toString(nonce));params.put("secretid",credentials.secretId());
        params.put("timestamp",Long.toString(timestamp));params.put("voice_format","1");params.put("voice_id",voiceId);
        params.put("sentence_strategy","0");
        if(!config.vocabularyId().isBlank())params.put("hotword_id",config.vocabularyId());
        var raw=new StringJoiner("&");params.forEach((k,v)->raw.add(k+"="+v));
        var mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(credentials.secretKey().getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
        String signature=Base64.getEncoder().encodeToString(mac.doFinal((root.substring(root.indexOf("://")+3)+"?"+raw).getBytes(StandardCharsets.UTF_8)));
        var query=new StringJoiner("&");params.forEach((k,v)->query.add(k+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));
        query.add("signature="+URLEncoder.encode(signature,StandardCharsets.UTF_8));
        return root+"?"+query;
    }
    private final ObjectMapper json;
    private final Map<String,JsonNode> confirmed=new HashMap<>();
    TencentAsr(ObjectMapper json){this.json=json;}
    List<JsonNode> accept(JsonNode response){
        var list=response.path("sentences").path("sentence_list");
        if(!list.isArray())return List.of();
        var result=new ArrayList<JsonNode>();var draft=new StringBuilder();
        for(var item:list){
            String text=item.path("sentence").asText(),id=item.path("sentence_id").asText();
            if(item.path("sentence_type").asInt()!=1){draft.append(text);continue;}
            var node=json.createObjectNode().put("sentence_id",id).put("text",text).put("sentence_end",true)
                .put("begin_time",item.path("start_time").asLong()).put("end_time",item.path("end_time").asLong());
            // -1 is unknown. The same sentence can later acquire a real speaker ID.
            if(item.hasNonNull("speaker_id") && item.path("speaker_id").asInt(-1)>=0)node.put("speaker_id",item.get("speaker_id").asText());
            if(!node.equals(confirmed.get(id))){confirmed.put(id,node);result.add(node);}
        }
        result.add(json.createObjectNode().put("text",draft.toString()).put("sentence_end",false));
        return result;
    }
}
