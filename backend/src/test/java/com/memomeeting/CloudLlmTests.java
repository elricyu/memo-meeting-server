package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudLlmTests {
    final ObjectMapper json=new ObjectMapper();
    @Test void normalizesKnownBailianIdsWithoutChangingOtherProvidersOrCustomDeployments() {
        assertEquals("qwen3.7-flash",CloudLlm.model("DASHSCOPE_CN","Qwen3.7-Flash"));
        assertEquals("qwen3.7-flash-2026-07-15",CloudLlm.model("DASHSCOPE_INTL","Qwen3.7-Flash-2026-07-15"));
        assertEquals("Custom-Qwen",CloudLlm.model("DASHSCOPE_CN","Custom-Qwen"));
        assertEquals("Qwen3.7-Flash",CloudLlm.model("SERVER","Qwen3.7-Flash"));
        assertEquals(false,CloudLlm.options(config("http://localhost"),Map.of()).get("enable_thinking"));
        assertFalse(CloudLlm.options(new AiConfigs.Config("DEEPSEEK","","deepseek-chat","","zh",false,"fixture"),Map.of()).containsKey("enable_thinking"));
    }
    @Test void distinguishesModelErrorsFromAuthAndNeverEchoesRawProviderContent() {
        var error=CloudLlm.rejected(json,404,"{\"error\":{\"code\":\"model_not_found\",\"message\":\"fixture-secret\"}}");
        assertEquals("MODEL_NOT_FOUND",error.code());assertTrue(error.getMessage().contains("qwen3.7-flash"));assertFalse(error.getMessage().contains("fixture-secret"));
        assertEquals("AUTH_FAILED",CloudLlm.rejected(json,401,"").code());
        assertEquals("NOT_FOUND",CloudLlm.rejected(json,404,"<html>fixture-secret</html>").code());
        assertEquals("RATE_LIMITED",CloudLlm.rejected(json,429,"").code());
    }
    @Test void summaryUsesFlashNonThinkingAndPreservesProviderFailure() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var request=new AtomicReference<String>();
        server.createContext("/chat/completions",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"error\":{\"code\":\"model_not_found\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var service=new SummaryService(mock(Store.class),mock(MeetingService.class),mock(AiConfigs.class),json);
        try {
            var error=assertThrows(CloudLlm.Rejected.class,()->service.call(config("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions"),"system","fixture"));
            assertEquals("MODEL_NOT_FOUND",error.code());
            var body=json.readTree(request.get());assertEquals("qwen3.7-flash",body.path("model").asText());assertFalse(body.path("enable_thinking").asBoolean(true));
        }finally{service.close();server.stop(0);}
    }
    @Test void connectionTestReportsTheSameModelFailureAsSummary() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var request=new AtomicReference<String>();
        server.createContext("/",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"error\":{\"code\":\"model_not_found\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var access=mock(Access.class);var configs=mock(AiConfigs.class);
        var input=new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","Qwen3.7-Flash","","zh",false,false,"fixture");
        when(access.admin()).thenReturn(new Identity(1,"fixture","测试",null));
        when(configs.candidate(1,"llm",input)).thenReturn(config("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
        try {
            var api=new AdminAiApi(access,configs,mock(CloudAsr.class),json,mock(RateLimits.class));
            var result=api.test("llm",input);
            assertFalse(result.ok());assertEquals("MODEL_NOT_FOUND",result.code());assertTrue(result.message().contains("qwen3.7-flash"));
            var body=json.readTree(request.get());assertEquals(16,body.path("max_tokens").asInt());assertFalse(body.path("enable_thinking").asBoolean(true));
        }finally{server.stop(0);}
    }
    AiConfigs.Config config(String url){return new AiConfigs.Config("DASHSCOPE_CN",url,"qwen3.7-flash","","zh",false,"fixture-key");}
}
