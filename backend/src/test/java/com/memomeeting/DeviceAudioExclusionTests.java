package com.memomeeting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:exclusions;DB_CLOSE_DELAY=-1"})
class DeviceAudioExclusionTests {
 static final String folder=System.getProperty("java.io.tmpdir")+"/memo-exclusion-"+UUID.randomUUID();
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("memo.data-dir",()->folder);}
 @Autowired Store store;@Autowired AudioFiles audio;@Autowired JdbcTemplate db;
 @Test void explicitControlIntervalsAreExcludedWithoutDeletingOriginalOrChangingTimeline() throws Exception {
  var m=store.create("测试","","test");var control=new Store.Segment("control",10,20,"打开成功");var discussion=new Store.Segment("discussion",30,40,"讨论打开成功这个提示");
  store.segment(m.id(),control);store.segment(m.id(),discussion);
  byte[] pcm=new byte[3200];Arrays.fill(pcm,(byte)7);
  try(var f=audio.open(m.id())){f.write(pcm);}audio.finalizeFile(m.id());
  byte[] original=Files.readAllBytes(audio.path(m.id()));
  audio.excludeRanges(m.id(),store.exclusionCandidates(m.id(),List.of("control")));store.excludeSegments(m.id(),List.of("control"),"实际设备播报");
  var remaining=store.segments(m.id());assertEquals(1,remaining.size());assertEquals("discussion",remaining.getFirst().id());
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM segments WHERE meeting_id=?",Integer.class,m.id()));
  byte[] filtered=Files.readAllBytes(audio.path(m.id()));assertEquals(original.length,filtered.length);
  assertArrayEquals(original,Files.readAllBytes(Path.of(folder,"audio",m.id()+".before-device-exclusions.wav")));
  for(int i=44+320;i<44+640;i++)assertEquals(0,filtered[i]);assertEquals(7,filtered[44+960]);
 }
}
