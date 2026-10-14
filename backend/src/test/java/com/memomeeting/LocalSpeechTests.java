package com.memomeeting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:localspeech;DB_CLOSE_DELAY=-1"})
class LocalSpeechTests {
 @Autowired LocalSpeech speech;@Autowired Store store;@Autowired AudioFiles files;@Autowired JdbcTemplate db;@Autowired Accounts accounts;@Autowired ObjectMapper json;
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("memo.data-dir",()->System.getProperty("java.io.tmpdir")+"/memo-local-"+UUID.randomUUID());}
 @AfterEach void disable(){db.update("DELETE FROM local_speech_config");db.update("DELETE FROM local_transcription_jobs");db.update("DELETE FROM segments");db.update("DELETE FROM speakers");db.update("DELETE FROM meetings");}
 Store.Meeting recording() throws Exception {var u=accounts.register("local_"+UUID.randomUUID().toString().substring(0,8),"测试","explicit-test-password");var m=store.createOwned("本地转录测试","","token","WEB",null,new Identity(u.id(),u.username(),u.displayName(),null));try(var f=files.open(m.id())){f.write(new byte[32000]);}files.finalizeFile(m.id());store.audioBytes(m.id(),32000);store.state(m.id(),"COMPLETED","");return store.get(m.id());}
 @Test void queueIsDurableIdempotentAndPreservesExistingTranscript() throws Exception {
  var uploaded=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var jobs=new HashSet<String>();
  server.createContext("/",exchange->{try{String p=exchange.getRequestURI().getPath();String body;
   if(p.equals("/health"))body="{\"status\":\"ready\"}";
   else if(p.equals("/v1/jobs")){String data=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);String id=data.split("name=\"request_id\"\\r\\n\\r\\n")[1].split("\\r\\n")[0];jobs.add(id);uploaded.incrementAndGet();body=json.writeValueAsString(Map.of("jobId",id));}
   else {String id=p.substring(p.lastIndexOf('/')+1);if(!jobs.contains(id)){exchange.sendResponseHeaders(404,-1);return;}body="{\"status\":\"completed\",\"result\":{\"segments\":[{\"startMs\":0,\"endMs\":500,\"text\":\"会议确认。\",\"speakerId\":\"SPEAKER_00\"}]}}";}
   byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
  }finally{exchange.close();}});server.start();
  try {
   String url="http://127.0.0.1:"+server.getAddress().getPort();var m=recording();var keep=recording();store.segment(keep.id(),new Store.Segment("manual",0,100,"人工修订"));
   speech.save(new LocalSpeech.Input(url,"test-secret",true,true));assertFalse(speech.view().toString().contains("test-secret"));assertFalse(db.queryForObject("SELECT token_cipher FROM local_speech_config",String.class).contains("test-secret"));
   speech.tick();assertEquals("RUNNING",store.localTranscription(m.id()).get("status"));assertEquals(1,uploaded.get());
   speech.recover();db.update("UPDATE local_transcription_jobs SET next_try=0");speech.tick();
   assertEquals("DONE",store.localTranscription(m.id()).get("status"));assertEquals("会议确认。",store.segments(m.id()).getFirst().text());assertEquals(1,store.speakers(m.id()).size());assertEquals(1,uploaded.get());
   speech.tick();assertEquals(1,uploaded.get());assertEquals("人工修订",store.segments(keep.id()).getFirst().text());assertEquals("",store.localTranscription(keep.id()).get("status"));
  }finally{server.stop(0);}
 }
 @Test void unconfiguredServiceNeverQueuesOrInvokesCloud() throws Exception {var m=recording();speech.tick();assertEquals("",store.localTranscription(m.id()).get("status"));assertTrue(store.segments(m.id()).isEmpty());}
}
