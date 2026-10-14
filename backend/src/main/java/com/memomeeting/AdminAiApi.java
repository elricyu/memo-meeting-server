package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiApi {
    public record TestResult(boolean ok,String type,String provider,String model,long elapsedMs,String code,String message) {}
    private final Access access;
    private final AiConfigs configs;
    private final CloudAsr asr;
    private final ObjectMapper json;
    private final RateLimits limits;
    @org.springframework.beans.factory.annotation.Autowired private Events events;
    @org.springframework.beans.factory.annotation.Autowired private Accounts accounts;
    private final AtomicBoolean testing=new AtomicBoolean();
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    public AdminAiApi(Access access,AiConfigs configs,CloudAsr asr,ObjectMapper json,RateLimits limits){this.access=access;this.configs=configs;this.asr=asr;this.json=json;this.limits=limits;}
    @GetMapping public Object read(){return configs.views(access.admin().userId());}
    @GetMapping("/models") public Object models(){access.admin();return AsrModels.catalog();}
    @PostMapping("/{type}") public Object save(@PathVariable String type,@RequestBody AiConfigs.Input input){
        var admin=access.admin();AiConfigs.type(type);
        var log=org.slf4j.LoggerFactory.getLogger(AdminAiApi.class);
        log.info("ai_configuration_save_requested type={} admin_id={}",type,admin.userId());
        var view=configs.save(admin.userId(),type,input);
        log.info("ai_configuration_saved type={} admin_id={} mode={} provider={} model={} ready={} updated_at={}",type,admin.userId(),view.mode(),view.provider(),view.model(),view.ready(),view.updatedAt());
        accounts.userIds().forEach(id->events.publish("user:"+id,"configuration",Map.of("changed",true)));
        return view;}
    @PostMapping("/{type}/test") public TestResult test(@PathVariable String type,@RequestBody AiConfigs.Input input){
        var admin=access.admin();limits.check("ai-test:"+admin.userId(),8);
        var config=configs.candidate(admin.userId(),type,input);
        if(!config.ready())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"尚未配置服务 Key");
        if(!testing.compareAndSet(false,true))throw new ResponseStatusException(HttpStatus.CONFLICT,"已有连接测试正在执行，请等待完成");
        long started=System.nanoTime();
        try {
            if(type.equals("asr")) {
                // This is a real task, with a short PCM silence frame, not just a TCP probe.
                try(var session=asr.connect(config,"",sentence->{})){
                    session.audio(new byte[3200]);session.finish();session.done.get(25,TimeUnit.SECONDS);
                }
            } else {
                var request=HttpRequest.newBuilder(URI.create(config.url())).timeout(Duration.ofSeconds(20))
                    .header("Authorization","Bearer "+config.apiKey()).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(CloudLlm.options(config,Map.of("model",config.model(),"max_tokens",16,
                        "messages",List.of(Map.of("role","user","content","回复：连接成功"))))))).build();
                var response=http.send(request,HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200){var rejected=CloudLlm.rejected(json,response.statusCode(),response.body());return result(false,type,config,started,rejected.code(),rejected.getMessage());}
                if(json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText().isBlank())
                    return result(false,type,config,started,"INVALID_RESPONSE","云端返回无效内容，连接测试未通过");
            }
            return result(true,type,config,started,"OK",type.equals("asr")?"识别服务握手与任务结束通过；真实语音准确率需要录音验证":"语言模型实际请求通过");
        } catch(Exception error){
            String code="CONNECTION_FAILED";
            for(Throwable e=error;e!=null;e=e.getCause()) {
                if(e instanceof DoubaoAsr.Rejected rejected)return result(false,type,config,started,"DOUBAO_"+rejected.code,rejected.userMessage());
                if(e instanceof TencentAsr.Rejected rejected)return result(false,type,config,started,rejected.resultCode(),rejected.userMessage());
                if(e instanceof WebSocketHandshakeException h && (h.getResponse().statusCode()==401 || h.getResponse().statusCode()==403))code="AUTH_FAILED";
                else if(e instanceof WebSocketHandshakeException h)return result(false,type,config,started,"HANDSHAKE_REJECTED","云端 WebSocket 握手被拒绝（HTTP "+h.getResponse().statusCode()+"），请检查服务开通及接口权限");
                if(e instanceof TimeoutException || e instanceof HttpTimeoutException)code="TIMEOUT";
            }
            return result(false,type,config,started,code,code.equals("AUTH_FAILED")?"Key 无效或缺少权限，请检查 Key 与地域":code.equals("TIMEOUT")?"云端连接或任务结束超时，测试未通过":"云端测试失败，请检查网络、Key、余额、模型权限和地域");
        } finally{testing.set(false);}
    }
    private TestResult result(boolean ok,String type,AiConfigs.Config config,long start,String code,String message){
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        org.slf4j.LoggerFactory.getLogger(AdminAiApi.class).info("ai_connection_test type={} provider={} model={} ok={} code={} elapsed_ms={}",type,config.provider(),config.model(),ok,code,elapsed);
        return new TestResult(ok,type,config.provider(),config.model(),elapsed,code,message);
    }
}
