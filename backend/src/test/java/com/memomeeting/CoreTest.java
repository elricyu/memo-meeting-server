package com.memomeeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:protocoltest;DB_CLOSE_DELAY=-1"})
class CoreTest {
    static final String data=System.getProperty("java.io.tmpdir")+"/memo-protocol-test-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("memo.data-dir",()->data);}
    @Autowired Store store;
    static byte[] frame(int sequence,long offset,byte[] pcm){
        return ByteBuffer.allocate(16+pcm.length).order(ByteOrder.LITTLE_ENDIAN).put(new byte[]{'M','M','0','1'}).putInt(sequence).putLong(offset).put(pcm).array();
    }
    @Test void frameSupportsUnsignedSequenceAndLargeOffset(){
        var decoded=AudioProtocol.decode(frame(-1,1L<<34,new byte[]{1,2}));
        assertEquals(4294967295L,decoded.sequence());assertEquals(1L<<34,decoded.offset());assertArrayEquals(new byte[]{1,2},decoded.pcm());
    }
    @Test void malformedFramesAreRejectedBeforeWritingAudio(){
        assertThrows(IllegalArgumentException.class,()->AudioProtocol.decode(new byte[18]));
        assertThrows(IllegalArgumentException.class,()->AudioProtocol.decode(frame(0,0,new byte[1])));
        assertThrows(IllegalArgumentException.class,()->AudioProtocol.decode(frame(0,1,new byte[2])));
        assertThrows(IllegalArgumentException.class,()->AudioProtocol.decode(frame(0,-2,new byte[2])));
        assertThrows(IllegalArgumentException.class,()->AudioProtocol.decode(frame(0,0,new byte[32002])));
    }
    @Test void idempotentCreateReturnsSameRecordAndCannotForgeRecorder(){
        String request=UUID.randomUUID().toString(),token="random-token-with-at-least-32-characters";
        var original=store.createIdempotent("会议","词表",token,"GLASSES",request);
        var retry=store.createIdempotent("会议","词表",token,"GLASSES",request);
        assertEquals(original.id(),retry.id());assertEquals("GLASSES",retry.source());
        assertThrows(ResponseStatusException.class,()->store.createIdempotent("会议","词表","forged","GLASSES",request));
        assertThrows(ResponseStatusException.class,()->store.createIdempotent("其他会议","词表",token,"GLASSES",request));
    }
    @Test void separateRequestIdsCreateSeparateRecords(){
        var a=store.createIdempotent("会议","","token","WEB",UUID.randomUUID().toString());
        var b=store.createIdempotent("会议","","token","WEB",UUID.randomUUID().toString());assertNotEquals(a.id(),b.id());
    }
}
