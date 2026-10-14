package com.memomeeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping({"/api/v1","/api"})
public class Api {
    private final Store store;
    private final MeetingService meetings;
    private final SummaryService summaries;
    private final Settings settings;
    private final Events events;
    private final AudioFiles audio;
    private final Access access;
    private final AiConfigs ai;
    public Api(Store store,MeetingService meetings,SummaryService summaries,Settings settings,Events events,AudioFiles audio,Access access,AiConfigs ai){this.store=store;this.meetings=meetings;this.summaries=summaries;this.settings=settings;this.events=events;this.audio=audio;this.access=access;this.ai=ai;}
    public record Create(@NotBlank @Size(max=200) String title,@Size(max=400) String context,
                         @Pattern(regexp="WEB|GLASSES|OTHER") String source,
                         @Pattern(regexp="[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") String clientRequestId,
                         @Size(min=32,max=256) String recorderToken) {}
    public record Created(Store.Meeting meeting,String recorderToken,int protocolVersion,Map<String,Object> audioSpec) {}
    @GetMapping("/config") public Object config(){long user=Access.current().userId();var asr=ai.resolve(user,"asr");var llm=ai.resolve(user,"llm");return Map.of("asrReady",asr.ready(),"llmReady",llm.ready(),"asrModel",asr.model(),"asrProvider",asr.provider(),"asrCapabilities",AsrModels.find(asr.provider(),asr.model()),"diarization",asr.diarization(),"maxSeconds",settings.maxSeconds(),"protocolVersion",1,"audioSpec",AudioProtocol.specification());}
    @GetMapping("/health") public Object health(){return Map.of("status","ok");}
    @GetMapping("/meetings") public Object list(){return store.listWithProgressFor(Access.current());}
    @PostMapping("/meetings") public Created create(@Valid @RequestBody Create input){
        if(input.clientRequestId()!=null && input.recorderToken()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"使用 clientRequestId 时必须同时提供随机 recorderToken");
        String token=input.recorderToken()==null?UUID.randomUUID()+"-"+UUID.randomUUID():input.recorderToken();
        var identity=Access.current();
        String source=identity.device()?"GLASSES":input.source()==null?"WEB":input.source();
        if(!identity.device() && source.equals("GLASSES"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"眼镜来源必须由已绑定设备创建");
        var m=store.createOwned(input.title().trim(),input.context()==null?"":input.context().trim(),token,source,input.clientRequestId(),identity);
        meetings.changed(m.id());return new Created(m,token,1,AudioProtocol.specification());
    }
    @GetMapping("/meetings/{id}") public Object detail(@PathVariable String id){access.meeting(id);return meetings.detail(id);}
    @GetMapping("/meetings/{id}/status") public Object status(@PathVariable String id){access.meeting(id);return store.get(id);}
    @GetMapping("/meetings/{id}/transcript") public Object transcript(@PathVariable String id){access.meeting(id);var detail=meetings.detail(id);return Map.of("segments",detail.segments(),"draft",detail.draft(),"speakers",detail.speakers());}
    public record SpeakerName(@NotBlank @Size(max=60) String name) {}
    public record ExcludeSegments(@NotEmpty @Size(max=100) List<@NotBlank @Size(max=100) String> segmentIds,@NotBlank @Size(max=200) String reason) {}
    @PostMapping("/meetings/{id}/transcript/exclusions") public Object exclusions(@PathVariable String id,@Valid @RequestBody ExcludeSegments input) throws IOException {
        access.meeting(id);meetings.reserve(id);
        try {
            var meeting=store.get(id);
            if(!Set.of("COMPLETED","INTERRUPTED","FAILED").contains(meeting.status()) || meeting.summaryStatus().equals("RUNNING"))throw new ResponseStatusException(HttpStatus.CONFLICT,"请等待录音和纪要处理结束");
            var candidates=store.exclusionCandidates(id,input.segmentIds());
            audio.excludeRanges(id,candidates);store.excludeSegments(id,input.segmentIds(),input.reason());meetings.changed(id);return meetings.detail(id);
        } finally {meetings.release(id);}
    }
    @PostMapping("/meetings/{id}/speakers/{speakerId}") public Object rename(@PathVariable String id,@PathVariable String speakerId,@Valid @RequestBody SpeakerName input){
        Access.user();access.meeting(id);
        var m=store.get(id);
        if(m.status().equals("REPROCESSING") || m.summaryStatus().equals("RUNNING"))throw new ResponseStatusException(HttpStatus.CONFLICT,"正在重建原文或生成纪要，请稍后修改姓名");
        store.renameSpeaker(id,speakerId,input.name().trim());meetings.changed(id);return meetings.detail(id);
    }
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter listEvents(){var identity=Access.user();return events.subscribe("user:"+identity.userId(),()->Map.of("ready",true),Access.actor(identity));}
    @GetMapping(value="/meetings/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter detailEvents(@PathVariable String id){access.meeting(id);return events.subscribe(id,()->meetings.detail(id),Access.actor(Access.current()));}
    @PostMapping("/meetings/{id}/finish") public Object finish(@PathVariable String id,@RequestHeader(value="X-Recorder-Token",required=false) String token){meetings.finishInterrupted(id,token);return meetings.detail(id);}
    @PostMapping("/meetings/{id}/transcription/start") public Object startTranscription(@PathVariable String id) throws Exception {meetings.startTranscription(id);return meetings.detail(id);}
    @PostMapping("/meetings/{id}/retranscribe") public Object retranscribe(@PathVariable String id,@RequestHeader(value="X-Recorder-Token",required=false) String token){meetings.retranscribe(id,token);return Map.of("accepted",true);}
    @PostMapping("/meetings/{id}/summary") public Object summary(@PathVariable String id,@RequestHeader(value="X-Recorder-Token",required=false) String token){summaries.generate(id,token);return Map.of("accepted",true);}
    @GetMapping("/meetings/{id}/audio") public ResponseEntity<Resource> audio(@PathVariable String id) throws IOException {
        access.meeting(id);var m=store.get(id);
        if(Set.of("CONNECTING","RECORDING","FINALIZING","REPROCESSING").contains(m.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"请等待录音处理结束");
        var path=audio.path(id);
        if(!Files.exists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"此记录没有音频");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(new FileSystemResource(path));
    }
    @GetMapping("/meetings/{id}/export") public ResponseEntity<String> export(@PathVariable String id){
        access.meeting(id);var d=meetings.detail(id);var text=new StringBuilder("# "+d.meeting().title()+"\n\n");
        if(!d.meeting().summary().isBlank()) text.append(d.meeting().summary()).append("\n\n");
        var names=new HashMap<String,String>();d.speakers().forEach(s->names.put(s.id(),s.name()));
        text.append("## 转录原文\n\n");d.segments().forEach(s->text.append("[").append(s.startMs()/1000).append("秒] ").append(s.speakerId()==null?"":names.getOrDefault(s.speakerId(),"未标记说话人")+"：").append(s.text()).append("\n\n"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).header("Content-Disposition","attachment; filename=meeting-"+id+".md").body(text.toString());
    }
}
