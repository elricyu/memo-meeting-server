package com.memomeeting;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class DoubaoTests {
    final ObjectMapper json=new ObjectMapper();
    byte[] response(String body,boolean last,boolean compressed) throws Exception {
        byte[] payload=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(compressed){var out=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(out)){gzip.write(payload);}payload=out.toByteArray();}
        return ByteBuffer.allocate(12+payload.length).put((byte)0x11).put((byte)(last?0x93:0x91)).put((byte)(compressed?0x11:0x10)).put((byte)0).putInt(last?-2:1).putInt(payload.length).put(payload).array();
    }
    @Test void decodesCompressedSequenceAndTerminalBitWithoutRelyingOnSequenceSign() throws Exception {
        var packet=response("{\"audio_info\":{\"duration\":100},\"result\":{\"text\":\"会议\"}}",true,true);
        var decoded=DoubaoAsr.decode(json,packet);assertTrue(decoded.terminal());assertEquals("会议",decoded.body().path("result").path("text").asText());
        ByteBuffer.wrap(packet).putInt(4,3);assertTrue(DoubaoAsr.decode(json,packet).terminal());
        assertFalse(DoubaoAsr.decode(json,response("{}",false,false)).terminal());
    }
    @Test void rejectsTruncatedFramesInvalidLengthsVersionsAndGzipBombs() throws Exception {
        var packet=response("{}",false,true);
        assertThrows(IOException.class,()->DoubaoAsr.decode(json,Arrays.copyOf(packet,packet.length-1)));
        var bad=packet.clone();bad[0]=0x21;assertThrows(IOException.class,()->DoubaoAsr.decode(json,bad));
        var length=packet.clone();ByteBuffer.wrap(length).putInt(8,Integer.MAX_VALUE);assertThrows(IOException.class,()->DoubaoAsr.decode(json,length));
        var bomb=response("{\"text\":\""+"x".repeat(DoubaoAsr.MAX_JSON)+"\"}",false,true);assertThrows(IOException.class,()->DoubaoAsr.decode(json,bomb));
        assertThrows(IOException.class,()->DoubaoAsr.decode(json,response("null",false,false)));
    }
    @Test void providerErrorsExposeControlledCodeOnly() throws Exception {
        byte[] secret="external-error-containing-secret".getBytes();
        byte[] error=ByteBuffer.allocate(12+secret.length).putInt(0x11f01000).putInt(45000081).putInt(secret.length).put(secret).array();
        var rejected=assertThrows(DoubaoAsr.Rejected.class,()->DoubaoAsr.decode(json,error));
        assertEquals(45000081,rejected.code);assertTrue(rejected.userMessage().contains("超时"));assertFalse(rejected.getMessage().contains("secret"));
        assertThrows(DoubaoAsr.Rejected.class,()->DoubaoAsr.decode(json,response("{\"code\":45000001,\"message\":\"secret\"}",false,false)));
    }
    @Test void lateSpeakerIdentityUsesStableIdsAndFullSnapshotsDoNotDuplicateText() throws Exception {
        var adapter=new DoubaoAsr(json);
        String initial="""
            {"result":{"utterances":[{"start_time":0,"end_time":500,"text":"项目","definite":true,"additions":{"speaker_id":""}},{"start_time":500,"end_time":700,"text":"讨论中","definite":false}]}}
            """;
        var first=adapter.accept(new DoubaoAsr.Response(json.readTree(initial),false));assertFalse(first.getFirst().has("speaker_id"));assertEquals("讨论中",first.getLast().path("text").asText());
        String identified=initial.replace("\"speaker_id\":\"\"","\"speaker_id\":\"0\"").replace("\"end_time\":500","\"end_time\":520");
        var next=adapter.accept(new DoubaoAsr.Response(json.readTree(identified),false));
        assertEquals(first.getFirst().path("sentence_id"),next.getFirst().path("sentence_id"));assertEquals("0",next.getFirst().path("speaker_id").asText());
        assertEquals(1,adapter.accept(new DoubaoAsr.Response(json.readTree(identified),false)).size());
        var finalResult=adapter.accept(new DoubaoAsr.Response(json.readTree(identified.replace("讨论中","收到。")),true));
        assertTrue(finalResult.getFirst().path("sentence_end").asBoolean());assertEquals("收到。",finalResult.getFirst().path("text").asText());assertEquals("",finalResult.getLast().path("text").asText());
        var unknown=adapter.accept(new DoubaoAsr.Response(json.readTree(initial.replace("\"speaker_id\":\"\"","\"speaker_id\":\"others\"")),true));assertFalse(unknown.getFirst().has("speaker_id"));
    }
    @Test void bothModesSendSinglePassRequestsAndAudioHasBinaryTerminalFlag() throws Exception {
        for(String model:List.of(AsrModels.DOUBAO_SENTENCE,AsrModels.DOUBAO_STREAM)){
            var config=new AiConfigs.Config("DOUBAO","",model,"","zh",model.equals(AsrModels.DOUBAO_SENTENCE),"test-key");
            JsonNode start=json.valueToTree(DoubaoAsr.start(json,config,"task",""));
            assertFalse(start.path("request").path("enable_nonstream").asBoolean());assertTrue(start.path("request").path("show_utterances").asBoolean());assertFalse(start.toString().contains("test-key"));
            assertEquals(16000,start.path("audio").path("rate").asInt());
        }
        JsonNode twoPass=json.valueToTree(DoubaoAsr.start(json,new AiConfigs.Config("DOUBAO","",AsrModels.DOUBAO_TWOPASS,"","zh",true,"test-key"),"task","项目与人名"));
        assertTrue(twoPass.path("request").path("enable_nonstream").asBoolean());
        assertEquals("项目与人名",json.readTree(twoPass.path("request").path("corpus").path("context").asText()).path("context_data").path(0).path("text").asText());
        byte[] pcm={0,1,2,3},packet=DoubaoAsr.packet(2,true,false,pcm);assertEquals(0x22,Byte.toUnsignedInt(packet[1]));
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(packet,8,packet.length-8))){assertArrayEquals(pcm,gzip.readAllBytes());}
    }
    @Test void capabilitiesDistinguishModesOfTheSameModelAndDoNotGuessUnknownModels(){
        var sentence=AsrModels.find("DOUBAO",AsrModels.DOUBAO_SENTENCE);var stream=AsrModels.find("DOUBAO",AsrModels.DOUBAO_STREAM);
        assertTrue(sentence.supportsDiarization("zh"));assertFalse(sentence.supportsDiarization("en"));assertFalse(sentence.partialResults());assertEquals("STREAMING_SENTENCE",sentence.speakerMode());
        assertTrue(stream.partialResults());assertFalse(stream.supportsDiarization("zh"));assertFalse(AsrModels.find("DOUBAO","unknown").supportsDiarization("zh"));
        assertFalse(AsrModels.find("DASHSCOPE_CN","fun-asr-realtime").supportsDiarization("zh"));
    }
}
