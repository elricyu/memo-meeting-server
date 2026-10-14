package com.memomeeting;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.*;

/** Volcengine SAUC binary v1. Two-pass is selected only by its explicit model mode. */
final class DoubaoAsr {
    static final int MAX_FRAME=1024*1024,MAX_JSON=4*1024*1024,CHUNK_BYTES=6400;
    static final String RESOURCE="volc.seedasr.sauc.duration";
    record Response(JsonNode body,boolean terminal) {}
    static final class Rejected extends IllegalStateException {
        final int code;
        Rejected(int code){super("豆包语音识别错误码 "+code);this.code=code;}
        String userMessage(){return switch(code){
            case 45000001 -> "豆包拒绝请求参数，请检查模型和识别模式";
            case 45000002 -> "豆包未收到有效音频，测试未通过，请检查音频输入";
            case 45000081 -> "豆包等待音频超时，请检查音频上传连接";
            case 45000151 -> "豆包拒绝音频格式，请检查 16kHz 单声道 PCM";
            case 55000031 -> "豆包服务繁忙，请稍后重试";
            default -> "豆包拒绝识别任务，请检查语音服务权限、额度及模型开通状态";
        }+"（豆包错误码 "+code+"）";}
    }
    static Map<String,Object> start(ObjectMapper json,AiConfigs.Config config,String taskId,String context) throws IOException{
        var request=new LinkedHashMap<String,Object>();
        request.put("model_name","bigmodel");request.put("enable_nonstream",AsrModels.DOUBAO_TWOPASS.equals(config.model()));
        request.put("enable_itn",true);request.put("enable_punc",true);request.put("show_utterances",true);
        request.put("enable_speaker_info",config.diarization());request.put("result_type","full");
        if(config.diarization())request.put("ssd_version","200");
        if(!context.isBlank())request.put("corpus",Map.of("context",json.writeValueAsString(Map.of("context_type","dialog_ctx","context_data",List.of(Map.of("text",context))))));
        // Preserve the vendor's segmentation defaults, without extra replay requests.
        return Map.of("user",Map.of("uid",taskId),"audio",Map.of("format","pcm","codec","raw","rate",16000,"bits",16,"channel",1),"request",request);
    }
    static byte[] packet(int type,boolean terminal,boolean json,byte[] payload) throws IOException {
        var compressed=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(compressed)){gzip.write(payload);}
        byte[] bytes=compressed.toByteArray();
        return ByteBuffer.allocate(8+bytes.length).put((byte)0x11).put((byte)((type<<4)|(terminal?2:0))).put((byte)(json?0x11:0x01)).put((byte)0).putInt(bytes.length).put(bytes).array();
    }
    static Response decode(ObjectMapper json,byte[] bytes) throws IOException {
        if(bytes.length<8 || bytes.length>MAX_FRAME)throw new IOException("豆包响应包长度无效");
        var buffer=ByteBuffer.wrap(bytes);
        int header=Byte.toUnsignedInt(buffer.get()),typeFlags=Byte.toUnsignedInt(buffer.get()),encoding=Byte.toUnsignedInt(buffer.get());buffer.get();
        int headerSize=(header&15)*4,type=typeFlags>>4,flags=typeFlags&15,compression=encoding&15;
        if(header>>4!=1 || headerSize<4 || headerSize>bytes.length-4 || flags>3 || compression>1 || (type!=9 && type!=15))throw new IOException("豆包响应协议无效");
        buffer.position(headerSize);
        if(type==15){if(buffer.remaining()<8)throw new IOException("豆包错误包不完整");int code=buffer.getInt(),length=buffer.getInt();if(length<0 || length!=buffer.remaining())throw new IOException("豆包错误包长度不匹配");throw new Rejected(code);}
        if(encoding>>4!=1)throw new IOException("豆包响应不是 JSON");
        if((flags&1)!=0){if(buffer.remaining()<8)throw new IOException("豆包序号不完整");buffer.getInt();}
        if(buffer.remaining()<4)throw new IOException("豆包响应长度缺失");
        int length=buffer.getInt();if(length<0 || length!=buffer.remaining())throw new IOException("豆包响应长度不匹配");
        byte[] payload=new byte[length];buffer.get(payload);
        if(compression==1)try(var gzip=new GZIPInputStream(new ByteArrayInputStream(payload))){payload=gzip.readNBytes(MAX_JSON+1);}
        if(payload.length>MAX_JSON)throw new IOException("豆包响应过大");
        JsonNode body=json.readTree(payload);if(body==null || !body.isObject())throw new IOException("豆包响应 JSON 无效");
        if(body.has("code") && body.path("code").asInt(-1)!=0 && body.path("code").asInt()!=20000000)throw new Rejected(body.path("code").asInt());
        return new Response(body,(flags&2)!=0);
    }
    private final ObjectMapper json;
    private final Map<Integer,JsonNode> confirmed=new HashMap<>();
    DoubaoAsr(ObjectMapper json){this.json=json;}
    List<JsonNode> accept(Response response){
        JsonNode body=response.body(),result=body.path("result");
        if(result.isArray())result=result.path(0);
        var utterances=result.path("utterances");var output=new ArrayList<JsonNode>();var draft=new StringBuilder();
        if(utterances.isArray())for(int i=0;i<utterances.size();i++){
            var utterance=utterances.get(i);String text=utterance.path("text").asText();
            if(!utterance.path("definite").asBoolean() && !response.terminal()){draft.append(text);continue;}
            if(text.isBlank())continue;
            // Full snapshots preserve utterance order. Do not use changing end times as IDs.
            var node=json.createObjectNode().put("sentence_id","doubao:"+i).put("text",text).put("sentence_end",true)
                .put("begin_time",utterance.path("start_time").asLong()).put("end_time",utterance.path("end_time").asLong());
            var additions=utterance.path("additions");
            if(additions.isTextual())try{additions=json.readTree(additions.asText());}catch(IOException ignored){additions=json.createObjectNode();}
            String speaker=utterance.path("speaker_id").asText(additions.path("speaker_id").asText());
            if(speaker.matches("[A-Za-z0-9_-]{1,128}") && !Set.of("-1","others","unknown").contains(speaker))node.put("speaker_id",speaker);
            if(!node.equals(confirmed.get(i))){confirmed.put(i,node);output.add(node);}
        }
        // With no utterance array the partial model can still supply plain text.
        if(!utterances.isArray() && result.hasNonNull("text")){
            String text=result.path("text").asText();
            if(response.terminal() && !text.isBlank())output.add(json.createObjectNode().put("sentence_id","doubao:0").put("text",text).put("sentence_end",true).put("begin_time",0).put("end_time",body.path("audio_info").path("duration").asLong()));
            else draft.append(text);
        }
        if(utterances.isArray() || result.has("text") || response.terminal())output.add(json.createObjectNode().put("sentence_end",false).put("text",draft.toString()));
        return output;
    }
}
