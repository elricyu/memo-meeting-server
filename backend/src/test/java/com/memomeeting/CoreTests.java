package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:coretests;DB_CLOSE_DELAY=-1","memo.asr-api-key=test-only"})
class CoreTests {
    @Autowired Store store;
    @Autowired MeetingService service;
    @Autowired ObjectMapper json;
    @TempDir Path audioRoot;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("memo.data-dir",()->System.getProperty("java.io.tmpdir")+"/memo-core-"+UUID.randomUUID());}
    @Test void recorderSecretIsHashedAndRequired(){
        var m=store.create("测试会议","术语","secret");
        assertTrue(store.authorized(m.id(),"secret"));assertFalse(store.authorized(m.id(),"wrong"));assertFalse(store.authorized(m.id(),null));
        assertFalse(json.valueToTree(m).has("recorder_hash"));
    }
    @Test void segmentUpsertsAvoidDuplicatesAndRemainOrdered(){
        var m=store.create("测试会议","","secret");
        store.segment(m.id(),new Store.Segment("b",1000,2000,"第二句"));
        store.segment(m.id(),new Store.Segment("a",0,900,"第一句"));
        store.segment(m.id(),new Store.Segment("a",0,950,"第一句。"));
        assertEquals(2,store.segments(m.id()).size());assertEquals("第一句。",store.segments(m.id()).getFirst().text());
    }
    @Test void replayReplacementInvalidatesOldSummary(){
        var m=store.create("会议","","secret");store.summary(m.id(),"DONE","旧纪要","");
        store.segment(m.id(),new Store.Segment("old",0,9,"旧文本"));
        store.replaceSegments(m.id(),List.of(new Store.Segment("new",0,9,"新文本")));
        assertEquals("新文本",store.segments(m.id()).getFirst().text());assertEquals("",store.get(m.id()).summary());
    }
    @Test void restartMarksActiveSessionsInterrupted(){
        var m=store.create("会议","","secret");store.state(m.id(),"RECORDING","");store.summary(m.id(),"RUNNING","","");
        store.recover();assertEquals("INTERRUPTED",store.get(m.id()).status());assertEquals("FAILED",store.get(m.id()).summaryStatus());
    }
    @Test void completedMeetingCannotBeReopenedOrMutatedByRejectedStart() throws Exception{
        var m=store.create("会议","","secret");store.state(m.id(),"COMPLETED","");
        assertThrows(ResponseStatusException.class,()->service.start(m.id(),"secret",mock(WebSocketSession.class)));
        assertEquals("COMPLETED",store.get(m.id()).status());
        // A rejected request must release its reservation.
        service.reserve(m.id());service.release(m.id());
    }
    @Test void unauthorizedStartDoesNotModifyMeeting(){
        var m=store.create("会议","","secret");
        assertThrows(ResponseStatusException.class,()->service.start(m.id(),"forged",mock(WebSocketSession.class)));
        assertEquals("WAITING",store.get(m.id()).status());
    }
    @Test void wavHeaderAndAppendAreValid() throws Exception{
        var settings=new Settings(audioRoot.toString(),"","","","","","","",new String[0],60);
        var files=new AudioFiles(settings);String id=UUID.randomUUID().toString();
        try(var file=files.open(id)){file.write(new byte[3200]);files.checkpoint(file);}
        try(var file=files.open(id)){file.write(new byte[3200]);files.checkpoint(file);}
        var data=Files.readAllBytes(files.path(id));var h=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(6444,data.length);assertEquals(6400,h.getInt(40));assertEquals(16000,h.getInt(24));assertEquals(6400,files.bytes(id));
        assertThrows(IllegalArgumentException.class,()->files.path("../secret"));
    }
    @Test void cloudTimeOffsetsAndSentenceIdsArePreserved() throws Exception{
        var segment=MeetingService.segment("task",5000,json.readTree("{\"begin_time\":170,\"end_time\":920,\"sentence_id\":1,\"text\":\"确认。\"}"));
        assertEquals("task:1",segment.id());assertEquals(5170,segment.startMs());assertEquals(5920,segment.endMs());
    }
}
