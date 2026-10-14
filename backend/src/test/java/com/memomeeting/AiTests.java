package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:aitests;DB_CLOSE_DELAY=-1"})
class AiTests {
    @Autowired ObjectMapper json;
    @Autowired Store store;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("memo.data-dir",()->System.getProperty("java.io.tmpdir")+"/memo-ai-"+UUID.randomUUID());}
    @Test void finalSpeakerTurnsAreSplitAndUnknownSpeakersRemainUnknown() throws Exception {
        var result=CloudAsr.normalizeDeepgram(json,json.readTree("""
        {"type":"Results","start":1,"duration":2,"is_final":true,"channel":{"alternatives":[{"transcript":"你好，收到。", "words":[{"punctuated_word":"你好，","start":1,"end":1.5,"speaker":0},{"punctuated_word":"收到。","start":2,"end":3,"speaker":1}]}]}}
        """));
        assertEquals(2,result.size());assertEquals("0",result.getFirst().path("speaker_id").asText());assertEquals("收到。",result.getLast().path("text").asText());assertEquals(2000,result.getLast().path("begin_time").asLong());
        var segment=MeetingService.segment("session",1000,result.getLast());assertEquals("session:1",segment.speakerId());assertEquals(3000,segment.startMs());
        var unknown=CloudAsr.normalizeDeepgram(json,json.readTree("{\"is_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"未知\",\"words\":[{\"word\":\"未知\"}]}]}}"));assertFalse(unknown.getFirst().has("speaker_id"));
    }
    @Test void speakerNamesSurviveUpdatesButAreResetWhenTranscriptIsReplaced(){
        var meeting=store.create("说话人测试","","test");
        store.segment(meeting.id(),new Store.Segment("a",0,100,"你好","session:0"));
        store.renameSpeaker(meeting.id(),"session:0","张三");
        store.segment(meeting.id(),new Store.Segment("b",100,200,"确认","session:0"));
        assertEquals("张三",store.speakers(meeting.id()).getFirst().name());
        store.replaceSegments(meeting.id(),List.of(new Store.Segment("c",0,100,"替换","new-session:0")));
        assertEquals(1,store.speakers(meeting.id()).size());assertEquals("说话人 1",store.speakers(meeting.id()).getFirst().name());
    }
    @Test void sonioxFinalTokensAppendOnceAndProvisionalTailIsReplaced() throws Exception {
        var normalizer=new SonioxTranscript(json);
        var first=normalizer.accept(json.readTree("""
        {"tokens":[{"text":"项目","is_final":true,"start_ms":0,"end_ms":100,"speaker":"1"},{"text":"进行中","is_final":false,"speaker":"1"}]}
        """));
        assertEquals("项目",first.getFirst().path("text").asText());
        assertEquals("进行中",first.getLast().path("text").asText());
        var next=normalizer.accept(json.readTree("""
        {"tokens":[{"text":"按计划推进。","is_final":true,"start_ms":100,"end_ms":500,"speaker":"1"},{"text":"收到","is_final":true,"start_ms":500,"end_ms":700,"speaker":"2"},{"text":"，我负责复核。","is_final":false,"speaker":"2"}]}
        """));
        assertEquals("项目按计划推进。",next.getFirst().path("text").asText());
        assertEquals(first.getFirst().path("sentence_id"),next.getFirst().path("sentence_id"));
        assertEquals("2",next.get(1).path("speaker_id").asText());
        var tail=normalizer.accept(json.readTree("""
        {"tokens":[{"text":"，我负责复核。","is_final":true,"start_ms":700,"end_ms":1000,"speaker":"2"},{"text":"<end>","is_final":true}],"finished":true}
        """));
        assertEquals("收到，我负责复核。",tail.getFirst().path("text").asText());
        assertEquals("",tail.getLast().path("text").asText());
        var options=CloudAsr.sonioxStart(new AiConfigs.Config("SONIOX","","stt-rt-v5","","zh",true,"test-key"),"技术会议");
        assertEquals(false,options.get("enable_endpoint_detection"));assertEquals(true,options.get("enable_speaker_diarization"));
        assertFalse(options.containsKey("api_key"));assertEquals("pcm_s16le",options.get("audio_format"));
    }
    @Test void tencentSnapshotsUpdateSpeakerIdentityWithoutDuplicatingFinalSentences() throws Exception {
        var normalizer=new TencentAsr(json);
        String unknown="{\"sentences\":{\"sentence_list\":[{\"sentence_id\":0,\"sentence_type\":1,\"sentence\":\"项目推进\",\"speaker_id\":-1,\"start_time\":0,\"end_time\":500}]}}";
        var first=normalizer.accept(json.readTree(unknown));assertFalse(first.getFirst().has("speaker_id"));
        assertEquals(1,normalizer.accept(json.readTree(unknown)).size());
        var identified=normalizer.accept(json.readTree(unknown.replace("-1","0")));
        assertEquals("0",identified.getFirst().path("speaker_id").asText());
        assertEquals(first.getFirst().path("sentence_id"),identified.getFirst().path("sentence_id"));
        var provisional=normalizer.accept(json.readTree("{\"sentences\":{\"sentence_list\":[{\"sentence_id\":1,\"sentence_type\":0,\"sentence\":\"收到\",\"speaker_id\":1}]}}"));
        assertEquals("收到",provisional.getLast().path("text").asText());assertFalse(provisional.getFirst().path("sentence_end").asBoolean());
    }
    @Test void tencentSignsV2PathAndEncodesBase64Signature() throws Exception {
        var config=new AiConfigs.Config("TENCENT","wss://asr.cloud.tencent.com/asr/v2","16k_zh_en_speaker_2.0","","zh",true,"1250000000:fixtureSecretId123:fixtureSecretKey123");
        String url=TencentAsr.signedUrl(config,"fixture-voice",1700000000,12345);
        assertEquals("wss://asr.cloud.tencent.com/asr/v2/1250000000?engine_model_type=16k_zh_en_speaker_2.0&expired=1700003600&needvad=1&nonce=12345&secretid=fixtureSecretId123&sentence_strategy=0&timestamp=1700000000&voice_format=1&voice_id=fixture-voice&signature=u1TDqHFg619H2ZC75StjMMITxGg%3D",url);
        assertFalse(url.contains("fixtureSecretKey123"));
        assertFalse(new TencentAsr.Credentials("1250000000","fixtureSecretId123","fixtureSecretKey123").toString().contains("fixtureSecret"));
    }
}
