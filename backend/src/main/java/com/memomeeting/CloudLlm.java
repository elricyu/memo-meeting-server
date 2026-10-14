package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Shared protocol handling for connection tests and meeting summaries. */
final class CloudLlm {
    static String model(String provider,String model) {
        // Correct display-name casing only for known public Qwen IDs; deployment IDs stay intact.
        if(provider.startsWith("DASHSCOPE") && model.matches("(?i)qwen(?:3\\.[5-8])?-(?:plus|max|flash)(?:-preview|-\\d{4}-\\d{2}-\\d{2})?"))
            return model.toLowerCase(Locale.ROOT);
        return model;
    }
    static Map<String,Object> options(AiConfigs.Config config,Map<String,Object> body) {
        var result=new HashMap<>(body);
        // Flash defaults to thinking; a 16-token probe must budget tokens for the actual answer.
        if(config.provider().startsWith("DASHSCOPE") && config.model().matches("qwen3\\.7-flash(?:-\\d{4}-\\d{2}-\\d{2})?"))
            result.put("enable_thinking",false);
        return result;
    }
    static final class Rejected extends IllegalStateException {
        private final String code;
        Rejected(String code,String message){super(message);this.code=code;}
        String code(){return code;}
    }
    static Rejected rejected(ObjectMapper json,int status,String body) {
        String providerCode="";
        try {var root=json.readTree(body);if(root!=null)providerCode=root.path("error").path("code").asText(root.path("code").asText());}
        catch(Exception ignored) { }
        // Never echo arbitrary provider bodies: they can contain credentials or meeting text.
        if(Set.of("ModelNotFound","model_not_found","model_not_supported").contains(providerCode))
            return new Rejected("MODEL_NOT_FOUND","纪要模型不存在或在当前地域、工作空间不可用（HTTP "+status+"）。请填写 API 模型 ID（注意大小写，例如 qwen3.7-flash），并确认模型权限。");
        return switch(status) {
            case 401,403 -> new Rejected("AUTH_FAILED","纪要服务认证或权限校验失败（HTTP "+status+"），请检查 Key、模型权限和地域。");
            case 404 -> new Rejected("NOT_FOUND","纪要服务未找到模型或接口（HTTP 404）。请检查 API 模型 ID 的大小写、服务地址、地域和工作空间；例如 qwen3.7-flash。");
            case 429 -> new Rejected("RATE_LIMITED","纪要服务限流或额度不足（HTTP 429），请检查配额与余额，稍后重试。");
            default -> new Rejected("PROVIDER_REJECTED","纪要云端请求失败（HTTP "+status+"），请检查服务配置或稍后重试。");
        };
    }
}
