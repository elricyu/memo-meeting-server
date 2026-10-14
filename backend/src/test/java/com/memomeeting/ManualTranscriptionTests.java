package com.memomeeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketSession;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:manualtranscription;DB_CLOSE_DELAY=-1"})
class ManualTranscriptionTests {
    @Autowired Store store;
    @Autowired AudioFiles files;
    @Autowired Settings settings;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("memo.data-dir",()->System.getProperty("java.io.tmpdir")+"/memo-manual-"+UUID.randomUUID());}
    @Test void recordingViewingAndStoppingNeverResolveAiOrConnectCloudByDefault() throws Exception {
        var cloud=mock(CloudAsr.class);var ai=mock(AiConfigs.class);
        var service=new MeetingService(store,files,mock(Events.class),cloud,settings,mock(Access.class),ai,mock(org.springframework.context.ApplicationEventPublisher.class));
        var socket=mock(WebSocketSession.class);when(socket.isOpen()).thenReturn(true);when(socket.getId()).thenReturn("record-only");
        var meeting=store.create("无识别配置也能录音","","secret");
        try {
            service.start(meeting.id(),"secret",socket,false,new Identity(1,"test","测试",null));
            service.audio(meeting.id(),socket,new byte[32000]);
            var detail=service.detail(meeting.id());assertFalse(detail.transcribing());assertFalse(detail.transcriptionStarting());assertTrue(detail.segments().isEmpty());assertEquals(32000,detail.meeting().audioBytes());
            service.stop(meeting.id(),socket);
            for(int i=0;i<100 && !store.get(meeting.id()).status().equals("COMPLETED");i++)Thread.sleep(10);
            assertEquals("COMPLETED",store.get(meeting.id()).status());assertEquals(32000,files.bytes(meeting.id()));
            verifyNoInteractions(ai,cloud);
        } finally {service.shutdown();}
    }
}
