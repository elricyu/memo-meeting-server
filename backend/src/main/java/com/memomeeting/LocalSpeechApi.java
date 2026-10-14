package com.memomeeting;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class LocalSpeechApi {
    private final Access access;private final LocalSpeech speech;
    public LocalSpeechApi(Access access,LocalSpeech speech){this.access=access;this.speech=speech;}
    @GetMapping("/admin/local-speech") public Object read(){access.admin();return speech.view();}
    @PostMapping("/admin/local-speech") public Object save(@RequestBody LocalSpeech.Input input){access.admin();return speech.save(input);}
    @PostMapping("/admin/local-speech/test") public Object test(@RequestBody LocalSpeech.Input input){access.admin();return speech.test(input);}
    @PostMapping("/meetings/{id}/local-transcription") public Object retry(@PathVariable String id){Access.user();access.meeting(id);speech.retry(id);return java.util.Map.of("accepted",true);}
}
