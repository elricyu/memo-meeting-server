package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:summarytests;DB_CLOSE_DELAY=-1"})
class SummaryTests {
    @Autowired Store store;
    @Autowired ObjectMapper json;
    @Autowired Accounts accounts;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("memo.data-dir",()->System.getProperty("java.io.tmpdir")+"/memo-summary-"+UUID.randomUUID());}
    @AfterEach void clearContext(){SecurityContextHolder.clearContext();}
    SummaryService parser(){return new SummaryService(store,mock(MeetingService.class),mock(AiConfigs.class),json);}
    @Test void parsesTitleAndMarkdownTogetherAndRejectsInvalidOrPartialResults() throws Exception {
        try(var service=new Closing(parser())){
            String response=json.writeValueAsString(Map.of("title","项目进度与方案复核","summary","## 摘要\n明确进度。"));
            var parsed=service.value.parse("```json\n"+response+"\n```");
            assertEquals("项目进度与方案复核",parsed.title());assertEquals("## 摘要\n明确进度。",parsed.summary());
            for(String invalid:List.of("## 摘要\n旧格式", "{\"summary\":\"纪要\"}", "{\"title\":\"标题\",\"summary\":\"\"}", "{\"title\":3,\"summary\":\"纪要\"}",json.writeValueAsString(Map.of("title","标题\n注入","summary","纪要")),json.writeValueAsString(Map.of("title","字".repeat(61),"summary","纪要")),response+"{}"))assertThrows(Exception.class,()->service.value.parse(invalid));
        }
    }
    @Test void generatedTitleAndSummaryAreSavedInOneFinalUpdateAndOriginalRetryStillWorks() throws Exception {
        String requestId=UUID.randomUUID().toString();var meeting=store.createIdempotent("眼镜会议记录","参考术语","secret","GLASSES",requestId);
        store.state(meeting.id(),"COMPLETED","");store.segment(meeting.id(),new Store.Segment("s",0,1000,"确认项目按计划推进。","speaker"));store.renameSpeaker(meeting.id(),"speaker","张三");
        var meetings=mock(MeetingService.class);var ai=mock(AiConfigs.class);
        when(ai.resolve(1,"llm")).thenReturn(new AiConfigs.Config("DASHSCOPE_CN","","qwen-plus","","zh",false,"test-only"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new Identity(1,"test","测试",null),null,List.of()));
        String output=json.writeValueAsString(Map.of("title","项目进度确认","summary","## 摘要\n张三确认按计划推进。"));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var service=new SummaryService(store,meetings,ai,json){@Override String call(AiConfigs.Config c,String system,String content){calls.incrementAndGet();assertTrue(content.contains("张三："));assertFalse(content.contains("[0秒]"));return output;}};
        try {
            service.generate(meeting.id(),"secret");await(meeting.id(),"DONE");
            var saved=store.get(meeting.id());assertEquals("项目进度确认",saved.title());assertEquals("## 摘要\n张三确认按计划推进。",saved.summary());assertEquals(1,calls.get());
            verify(meetings,timeout(2000).atLeast(2)).changed(meeting.id());
            assertEquals(meeting.id(),store.createIdempotent("眼镜会议记录","参考术语","secret","GLASSES",requestId).id());
            assertThrows(ResponseStatusException.class,()->store.createIdempotent("不同的创建标题","参考术语","secret","GLASSES",requestId));
            assertThrows(ResponseStatusException.class,()->store.createIdempotent("眼镜会议记录","参考术语","wrong-token","GLASSES",requestId));
        }finally{service.close();}
    }
    @Test void invalidCloudResponseKeepsPreviousTitleAndSummary() throws Exception {
        var meeting=store.create("原会议标题","","secret");store.state(meeting.id(),"COMPLETED","");store.segment(meeting.id(),new Store.Segment("s",0,1000,"原文"));store.summary(meeting.id(),"DONE","原纪要","");
        var meetings=mock(MeetingService.class);var ai=mock(AiConfigs.class);
        when(ai.resolve(1,"llm")).thenReturn(new AiConfigs.Config("DASHSCOPE_CN","","qwen-plus","","zh",false,"test-only"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new Identity(1,"test","测试",null),null,List.of()));
        var service=new SummaryService(store,meetings,ai,json){@Override String call(AiConfigs.Config c,String system,String content){return "{\"title\":\"新标题\"}";}};
        try {service.generate(meeting.id(),"secret");await(meeting.id(),"FAILED");var saved=store.get(meeting.id());assertEquals("原会议标题",saved.title());assertEquals("原纪要",saved.summary());verify(meetings,timeout(2000)).release(meeting.id());}finally{service.close();}
    }

    @Test void normalCompletionAutoGeneratesWithOwnerConfigurationWithoutBrowserAuthenticationAndOnlyOnce() throws Exception {
        var owner=accounts.register("sum_"+UUID.randomUUID().toString().replace("-", "").substring(0,12),"会议用户","test-only-password");
        var meeting=store.createOwned("眼镜会议记录","","secret","GLASSES",null,new Identity(owner.id(),owner.username(),owner.displayName(),null));
        store.state(meeting.id(),"COMPLETED","");store.segment(meeting.id(),new Store.Segment("s",0,1000,"确认项目按计划推进。"));
        var meetings=mock(MeetingService.class);var ai=mock(AiConfigs.class);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(ai.resolve(owner.id(),"llm")).thenReturn(new AiConfigs.Config("DASHSCOPE_CN","","qwen-plus","","zh",false,"owner-test-key"));
        String response=json.writeValueAsString(Map.of("title","项目进度确认","summary","## 摘要\n按计划推进。"));
        var service=new SummaryService(store,meetings,ai,json){@Override String call(AiConfigs.Config c,String system,String content){calls.incrementAndGet();return response;}};
        SecurityContextHolder.clearContext();
        try {
            var event=new MeetingService.RecordingCompleted(meeting.id());service.recordingCompleted(event);service.recordingCompleted(event);await(meeting.id(),"DONE");service.recordingCompleted(event);
            assertEquals(1,calls.get());assertEquals("项目进度确认",store.get(meeting.id()).title());verify(ai).resolve(owner.id(),"llm");verify(meetings,never()).authorize(anyString(),any());verify(meetings,timeout(2000)).release(meeting.id());
        }finally{service.close();}
    }
    @Test void autoGenerationSkipsInterruptedMeetingsAndReportsMissingKeyWithoutLosingAudioOrTitle() throws Exception {
        var owner=accounts.register("sum_"+UUID.randomUUID().toString().replace("-", "").substring(0,12),"会议用户","test-only-password");
        var meeting=store.createOwned("保留原标题","","secret","WEB",null,new Identity(owner.id(),owner.username(),owner.displayName(),null));
        store.state(meeting.id(),"INTERRUPTED","");store.audioBytes(meeting.id(),32000);store.segment(meeting.id(),new Store.Segment("s",0,1000,"已有文字"));
        var meetings=mock(MeetingService.class);var ai=mock(AiConfigs.class);
        when(ai.resolve(owner.id(),"llm")).thenReturn(new AiConfigs.Config("DASHSCOPE_CN","","qwen-plus","","zh",false,""));
        try(var wrapper=new Closing(new SummaryService(store,meetings,ai,json))){
            var event=new MeetingService.RecordingCompleted(meeting.id());wrapper.value.recordingCompleted(event);
            verifyNoInteractions(ai);assertEquals("IDLE",store.get(meeting.id()).summaryStatus());
            store.state(meeting.id(),"COMPLETED","");wrapper.value.recordingCompleted(event);
            var saved=store.get(meeting.id());assertEquals("FAILED",saved.summaryStatus());assertTrue(saved.summaryError().contains("尚未配置"));assertEquals("保留原标题",saved.title());assertEquals(32000,saved.audioBytes());verify(meetings,never()).reserve(anyString());
        }
    }
    void await(String id,String status) throws InterruptedException {for(int i=0;i<200;i++){if(store.get(id).summaryStatus().equals(status))return;Thread.sleep(10);}fail("summary did not reach "+status);}
    record Closing(SummaryService value) implements AutoCloseable {public void close(){value.close();}}
}
