package com.memomeeting;

import java.util.*;

/** Capabilities belong to a model and an access mode, never just to a vendor. */
final class AsrModels {
    static final String DOUBAO_SENTENCE="doubao-seed-asr-2.0";
    static final String DOUBAO_TWOPASS="doubao-seed-asr-2.0-twopass";
    static final String DOUBAO_STREAM="doubao-seed-asr-2.0-streaming";
    record Model(String provider,String model,String label,boolean partialResults,boolean twoPass,String speakerMode,boolean speakerRequired,List<String> languages,String note,String documentation) {
        boolean supportsDiarization(String language){return !speakerMode.equals("NONE") && languages.contains(language);}
    }
    static List<Model> all(){
        var models=new ArrayList<Model>();
        models.add(new Model("DOUBAO",DOUBAO_TWOPASS,"豆包 2.0 · 二遍实时分人（效果优先）",true,true,"REALTIME",false,List.of("zh"),"逐字实时输出，句尾精校并区分说话人，最多 10 人；姓名可即时修改。使用同一 2.0 小时计费资源，官方价目未单列二遍加价。","https://docs.volcengine.com/docs/DoubaoVoice/speaker-separation?lang=zh"));
        models.add(new Model("DOUBAO",DOUBAO_SENTENCE,"豆包 2.0 · 单遍句级分人",false,false,"STREAMING_SENTENCE",false,List.of("zh"),"单遍识别，会议中按句返回文字和说话人，最多 10 人。不是逐字字幕；服务可能缓冲约 15 秒。中文分人，不自动认姓名。","https://docs.volcengine.com/docs/DoubaoVoice/speaker-separation?lang=zh"));
        models.add(new Model("DOUBAO",DOUBAO_STREAM,"豆包 2.0 · 单遍逐字转录",true,false,"NONE",false,List.of("zh"),"逐字返回文字，不支持此模式下的说话人分离。系统不启用二遍识别。","https://docs.volcengine.com/docs/6561/1354869?lang=zh"));
        models.add(new Model("TENCENT","16k_zh_en_speaker_2.0","腾讯大模型 2.0 · 实时分人",true,false,"REALTIME",true,List.of("zh"),"单声道实时区分说话人，中英及方言识别。","https://cloud.tencent.com/document/product/1093/128143"));
        models.add(new Model("SONIOX","stt-rt-v5","Soniox v5 · 实时分人",true,false,"REALTIME",false,List.of("zh","en","multi","ja","ko"),"实时文字和说话人编号，姓名可在网页修改。","https://soniox.com/docs/stt/rt/real-time-transcription"));
        for(String model:List.of("nova-3","nova-3-general","nova-2","nova-2-general"))models.add(new Model("DEEPGRAM",model,model+" · 实时分人",true,false,"REALTIME",false,List.of("zh","en","multi","ja","ko"),"实时文字和说话人编号，具体语言效果需用录音验证。","https://developers.deepgram.com/docs/diarization"));
        for(String provider:List.of("DASHSCOPE_CN","DASHSCOPE_INTL"))for(String model:List.of("fun-asr-realtime","paraformer-realtime-v2"))models.add(bailian(provider,model));
        return List.copyOf(models);
    }
    /** Only models exercised successfully in production, plus the explicitly requested new integration. */
    static List<Model> catalog(){return all().stream().filter(m->m.provider().equals("DASHSCOPE_CN") && m.model().equals("fun-asr-realtime") || m.provider().equals("DOUBAO") && m.model().equals(DOUBAO_TWOPASS)).toList();}
    private static Model bailian(String provider,String model){return new Model(provider,model,model+" · 实时转录",true,false,"NONE",false,List.of("zh"),"此实时模型支持转录，不支持实时分人。","https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide");}
    static Model find(String provider,String model){
        var found=all().stream().filter(m->m.provider().equals(provider) && m.model().equals(model)).findFirst();
        if(found.isPresent())return found.get();
        // Dated variants of these integrated realtime families have the same protocol limits.
        if(provider.startsWith("DASHSCOPE") && model.matches("(fun-asr-realtime|paraformer-realtime)(-[A-Za-z0-9-]+)?"))return bailian(provider,model);
        return new Model(provider,model,model,false,false,"NONE",false,List.of(),"此模型的能力尚未验证，不能启用说话人分离。","");
    }
}
