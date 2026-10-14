package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import java.util.*;

@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer,WebMvcConfigurer {
    private final MeetingService meetings;
    private final Settings settings;
    private final ObjectMapper json;
    public WebConfig(MeetingService meetings,Settings settings,ObjectMapper json){this.meetings=meetings;this.settings=settings;this.json=json;}
    @Override public void addCorsMappings(CorsRegistry registry){registry.addMapping("/api/**").allowedOrigins(settings.allowedOrigins()).allowedMethods("GET","POST").allowedHeaders("Content-Type","X-Recorder-Token");}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(new Recorder(),"/ws/record/{id}","/ws/v1/meetings/{id}/audio").setAllowedOrigins(settings.allowedOrigins());}
    private class Recorder extends AbstractWebSocketHandler {
        String id(WebSocketSession session){String path=Objects.requireNonNull(session.getUri()).getPath();var parts=path.split("/");return versioned(session)?parts[4]:parts[3];}
        boolean versioned(WebSocketSession session){return Objects.requireNonNull(session.getUri()).getPath().startsWith("/ws/v1/");}
        @Override public void afterConnectionEstablished(WebSocketSession session){
            session.setBinaryMessageSizeLimit(32016);session.setTextMessageSizeLimit(4096);
            java.util.concurrent.CompletableFuture.delayedExecutor(10,java.util.concurrent.TimeUnit.SECONDS).execute(()->{
                if(session.isOpen() && !session.getAttributes().containsKey("started"))
                    try{session.close(CloseStatus.POLICY_VIOLATION);}catch(java.io.IOException ignored){}
            });
        }
        @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {
            try {
                var data=json.readTree(message.getPayload());
                switch(data.path("type").asText()){
                    // Recorder token is sent in the first frame, never in a URL or log.
                    case "start" -> {
                        if(session.getAttributes().putIfAbsent("started",true)!=null) throw new IllegalArgumentException("同一连接只能启动一次");
                        if(versioned(session)) {
                            var audio=data.path("audio");
                            if(data.path("protocolVersion").asInt()!=1 || !audio.path("format").asText().equals("pcm_s16le") || audio.path("sampleRate").asInt()!=16000 || audio.path("channels").asInt()!=1 || audio.path("bitsPerSample").asInt()!=16)
                                throw new IllegalArgumentException("v1 必须声明单声道 16kHz PCM16LE 音频参数");
                        }
                        meetings.start(id(session),data.path("token").asText(),session,versioned(session),Access.from(session.getPrincipal()));
                    }
                    case "stop" -> meetings.stop(id(session),session);
                    case "ping" -> meetings.ping(id(session),session);
                    default -> throw new IllegalArgumentException("无效的录音消息");
                }
            } catch(Exception e){reject(session,e);}
        }
        @Override protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message) throws Exception {
            try {byte[] bytes=new byte[message.getPayload().remaining()];message.getPayload().get(bytes);meetings.audio(id(session),session,bytes);}
            catch(Exception e){reject(session,e);}
        }
        private void reject(WebSocketSession session,Exception error) throws Exception {
            if(session.isOpen()) {
                synchronized(session){session.sendMessage(new TextMessage(json.writeValueAsString(Map.of("type","error","code",error instanceof IllegalArgumentException?"INVALID_PROTOCOL":"RECORDING_REJECTED","message",error instanceof org.springframework.web.server.ResponseStatusException e ? Objects.requireNonNullElse(e.getReason(),"请求失败") : error instanceof IllegalArgumentException ? error.getMessage() : "录音请求失败，请检查连接或记录状态"))));}
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        }
        @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){meetings.disconnect(id(session),session);}
        @Override public void handleTransportError(WebSocketSession session,Throwable exception) throws Exception{meetings.disconnect(id(session),session);if(session.isOpen()) session.close(CloseStatus.SERVER_ERROR);}
    }
}
