package com.memomeeting;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AiConfigs {
    public record Config(String provider,String url,String model,String vocabularyId,String language,boolean diarization,@JsonIgnore String apiKey) {
        public boolean ready(){return apiKey!=null && !apiKey.isBlank();}
        @Override public String toString(){return "AI["+provider+","+model+",key=redacted]";}
    }
    public record Input(String mode,String provider,String model,String vocabularyId,String language,boolean diarization,boolean reuseAsrKey,String apiKey,String workspaceId,String appId,String secretId,String secretKey) {
        public Input(String mode,String provider,String model,String vocabularyId,String language,boolean diarization,boolean reuseAsrKey,String apiKey){this(mode,provider,model,vocabularyId,language,diarization,reuseAsrKey,apiKey,"","","","");}
        public Input(String mode,String provider,String model,String vocabularyId,String language,boolean diarization,boolean reuseAsrKey,String apiKey,String workspaceId){this(mode,provider,model,vocabularyId,language,diarization,reuseAsrKey,apiKey,workspaceId,"","","");}
        @Override public String toString(){return "AI input[key=redacted]";}
    }
    public record View(String type,String mode,String provider,String url,String model,String vocabularyId,String language,boolean diarization,boolean supportsDiarization,boolean reuseAsrKey,boolean keySet,boolean ready,String updatedAt,String workspaceId,String appId,AsrModels.Model capabilities) {}
    private record Row(String provider,String model,String vocabulary,String language,boolean diarization,boolean reuse,String key,String updated,String workspace) {}
    private final JdbcTemplate db;
    private final Settings defaults;
    private final SecretBox secrets;
    public AiConfigs(JdbcTemplate db,Settings defaults,SecretBox secrets){this.db=db;this.defaults=defaults;this.secrets=secrets;}
    public static void type(String type){if(!Set.of("asr","llm").contains(type))bad("AI 类型应为 asr 或 llm");}
    private Row row(long user,String type){return db.query("SELECT * FROM global_ai_configs WHERE kind=?",(r,n)->new Row(r.getString("provider"),r.getString("model"),r.getString("vocabulary_id"),r.getString("language"),r.getBoolean("diarization"),r.getBoolean("reuse_asr_key"),r.getString("key_cipher"),r.getString("updated_at"),r.getString("workspace_id")),type).stream().findFirst().orElse(null);}
    public Config resolve(long user,String type){type(type);var row=row(user,type);return row==null?server(type):account(user,type,row);}
    private Config server(String type){
        boolean asr=type.equals("asr");String url=asr?defaults.asrUrl():defaults.llmUrl();
        String provider=url.contains("dashscope-intl.")?"DASHSCOPE_INTL":url.contains("dashscope.")?"DASHSCOPE_CN":url.contains("api.deepseek.com")?"DEEPSEEK":"SERVER";
        if(asr && Set.of("DEEPGRAM","SONIOX","TENCENT","DOUBAO").contains(defaults.asrProvider()))provider=defaults.asrProvider();
        return new Config(provider,url,asr?defaults.asrModel():CloudLlm.model(provider,defaults.llmModel()),asr?defaults.vocabularyId():"","zh",asr && AsrModels.find(provider,defaults.asrModel()).supportsDiarization("zh"),asr?defaults.asrApiKey():defaults.llmApiKey());
    }
    private Config account(long user,String type,Row row){
        String key=secrets.open(0,"GLOBAL_"+type,row.key());
        if(row.reuse()) {var asr=resolve(user,"asr");key=asr.provider().equals(row.provider())?asr.apiKey():"";}
        return new Config(row.provider(),endpoint(type,row.provider(),row.workspace(),row.model()),type.equals("llm")?CloudLlm.model(row.provider(),row.model()):row.model(),row.vocabulary(),row.language(),row.diarization(),key);
    }
    public Map<String,View> views(long user){return Map.of("asr",view(user,"asr"),"llm",view(user,"llm"));}
    public View view(long user,String type){
        type(type);var row=row(user,type);var config=row==null?server(type):account(user,type,row);
        return new View(type,row==null?"ENVIRONMENT":"GLOBAL",config.provider(),config.url(),config.model(),config.vocabularyId(),config.language(),config.diarization(),type.equals("asr") && AsrModels.find(config.provider(),config.model()).supportsDiarization(config.language()),row!=null && row.reuse(),row!=null && !row.key().isBlank(),config.ready(),row==null?null:row.updated(),row==null?"":row.workspace(),config.provider().equals("TENCENT") && config.ready()?TencentAsr.credentials(config.apiKey()).appId():"",type.equals("asr")?AsrModels.find(config.provider(),config.model()):null);
    }
    /** Resolve an unsaved draft. Blank keys retain only credentials for the same provider. */
    public Config candidate(long user,String type,Input input){
        type(type);if(input==null)bad("缺少 AI 配置");
        if("ENVIRONMENT".equals(input.mode()))return server(type);
        if(!"GLOBAL".equals(input.mode()))bad("请选择管理员统一配置或环境配置");
        String provider=text(input.provider(),40),model=text(input.model(),128),vocabulary=text(input.vocabularyId(),128),language=text(input.language(),16);
        if(type.equals("llm"))model=CloudLlm.model(provider,model);
        String url=endpoint(type,provider,text(input.workspaceId(),64),model);
        if(!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))bad("模型 ID 格式无效");
        if(type.equals("asr")) {
            if(provider.equals("DOUBAO")) {
                if(!Set.of(AsrModels.DOUBAO_SENTENCE,AsrModels.DOUBAO_STREAM,AsrModels.DOUBAO_TWOPASS).contains(model))bad("请选择已接入的豆包 2.0 识别模式");
                if(!vocabulary.isBlank())bad("豆包不使用百炼热词表 ID");
                language="zh";
            } else if(provider.equals("DEEPGRAM")) {
                if(!Set.of("nova-3","nova-3-general","nova-2","nova-2-general").contains(model))bad("Deepgram 请选择 Nova 通用模型");
                if(!Set.of("zh","en","multi","ja","ko").contains(language))bad("请选择受支持的识别语言");
                if(!vocabulary.isBlank())bad("Deepgram 不使用百炼热词表 ID");
            } else if(provider.equals("TENCENT")) {
                if(!model.equals("16k_zh_en_speaker_2.0"))bad("腾讯云实时分人请选择 16k_zh_en_speaker_2.0");
                if(!input.diarization())bad("此腾讯云模型默认开启实时分人");
                language="zh";
            } else if(provider.equals("SONIOX")) {
                if(!model.equals("stt-rt-v5"))bad("Soniox 请选择 stt-rt-v5 实时模型");
                if(!Set.of("zh","en","multi","ja","ko").contains(language))bad("请选择受支持的识别语言");
                if(!vocabulary.isBlank())bad("Soniox 不使用百炼热词表 ID，可在会议参考术语中填写");
            } else {
                if(!model.matches("(fun-asr-realtime|paraformer-realtime)(-[A-Za-z0-9-]+)?"))bad("此协议只支持 Fun-ASR / Paraformer 实时模型");

                language="zh";
            }
            var capabilities=AsrModels.find(provider,model);
            if(input.diarization() && !capabilities.supportsDiarization(language))bad("所选模型与语言不支持说话人分离，请选择具有分人能力的模型");
        } else if(input.diarization())bad("说话人分离属于语音识别配置");
        String key=text(input.apiKey(),2048);
        if(!key.isBlank() && !key.matches("[!-~]+"))bad("API Key 不能包含空格或换行");
        var old=row(user,type);
        if(provider.equals("TENCENT")) {
            String appId=text(input.appId(),20),secretId=text(input.secretId(),256),secretKey=text(input.secretKey(),256);
            if(!appId.matches("[0-9]{5,20}"))bad("请填写腾讯云数字 AppID");
            if(!secretId.isBlank() || !secretKey.isBlank()) {
                if(!secretId.matches("[A-Za-z0-9_-]{10,256}") || !secretKey.matches("[A-Za-z0-9_-]{10,256}"))bad("更新腾讯凭证时请同时填写有效的 SecretId 和 SecretKey");
                key=new TencentAsr.Credentials(appId,secretId,secretKey).encoded();
            } else if(old!=null && old.provider().equals(provider)) {
                key=secrets.open(0,"GLOBAL_"+type,old.key());
                if(!TencentAsr.credentials(key).appId().equals(appId))bad("更换 AppID 时需要重新填写 SecretId 和 SecretKey");
            } else bad("请填写腾讯云 SecretId 和 SecretKey");
        }
        if(input.reuseAsrKey()) {
            if(!type.equals("llm") || !provider.startsWith("DASHSCOPE"))bad("只有百炼纪要可以复用同地域的转录 Key");
            var asr=resolve(user,"asr");if(!asr.ready() || !asr.provider().equals(provider))bad("请先保存同地域的百炼转录 Key");
            key=asr.apiKey();
        } else if(key.isBlank() && old!=null && old.provider().equals(provider) && !old.reuse())key=secrets.open(0,"GLOBAL_"+type,old.key());
        if(key.isBlank())bad("请填写此服务的 API Key");
        return new Config(provider,url,model,vocabulary,language,input.diarization(),key);
    }
    @Transactional public synchronized View save(long user,String type,Input input){
        var config=candidate(user,type,input);
        // Changing ASR provider must preserve a Bailian summary key that used to be shared.
        if(type.equals("asr")) {
            var llm=row(user,"llm");
            if(llm!=null && llm.reuse() && !llm.provider().equals(config.provider())) {
                var previous=account(user,"llm",llm);
                if(previous.ready())db.update("UPDATE global_ai_configs SET reuse_asr_key=FALSE,key_cipher=? WHERE kind='llm'",secrets.seal(0,"GLOBAL_llm",previous.apiKey()));
            }
        }
        if("ENVIRONMENT".equals(input.mode()))db.update("DELETE FROM global_ai_configs WHERE kind=?",type);
        else db.update("MERGE INTO global_ai_configs(kind,provider,model,vocabulary_id,language,workspace_id,diarization,reuse_asr_key,key_cipher,updated_by,updated_at) KEY(kind) VALUES(?,?,?,?,?,?,?,?,?,?,?)",type,config.provider(),config.model(),config.vocabularyId(),config.language(),text(input.workspaceId(),64),config.diarization(),input.reuseAsrKey(),input.reuseAsrKey()?"":secrets.seal(0,"GLOBAL_"+type,config.apiKey()),user,Instant.now().toString());
        return view(user,type);
    }
    static String endpoint(String type,String provider){return endpoint(type,provider,"");}
    static String endpoint(String type,String provider,String workspace){return endpoint(type,provider,workspace,"");}
    static String endpoint(String type,String provider,String workspace,String model){
        if(workspace!=null && !workspace.isBlank()){
            if(!workspace.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || !Set.of("DASHSCOPE_CN","DASHSCOPE_INTL").contains(provider))bad("工作空间 ID 格式或服务商无效");
            String host=workspace+(provider.equals("DASHSCOPE_CN")?".cn-beijing.maas.aliyuncs.com":".ap-southeast-1.maas.aliyuncs.com");
            return type.equals("asr")?"wss://"+host+"/api-ws/v1/inference":"https://"+host+"/compatible-mode/v1/chat/completions";
        }
        boolean asr=type.equals("asr");
        return switch(provider) {
            case "DASHSCOPE_CN" -> asr?"wss://dashscope.aliyuncs.com/api-ws/v1/inference":"https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
            case "DASHSCOPE_INTL" -> asr?"wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference":"https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions";
            case "DOUBAO" -> {if(!asr)bad("豆包语音仅用于转录");yield "wss://openspeech.bytedance.com/api/v3/sauc/"+(AsrModels.DOUBAO_SENTENCE.equals(model) || model.isBlank()?"bigmodel_nostream":"bigmodel_async");}
            case "TENCENT" -> {if(!asr)bad("腾讯语音识别仅用于实时转录");yield "wss://asr.cloud.tencent.com/asr/v2";}
            case "SONIOX" -> {if(!asr)bad("Soniox 仅用于语音识别");yield "wss://stt-rt.soniox.com/transcribe-websocket";}
            case "DEEPGRAM" -> {if(!asr){bad("Deepgram 仅用于语音识别");}yield "wss://api.deepgram.com/v1/listen";}
            case "DEEPSEEK" -> {if(asr){bad("DeepSeek 仅用于纪要");}yield "https://api.deepseek.com/chat/completions";}
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"此服务尚未接入，请选择列表中的服务");
        };
    }
    static String text(String value,int max){String v=value==null?"":value.trim();if(v.length()>max)bad("配置字段过长");return v;}
    static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
