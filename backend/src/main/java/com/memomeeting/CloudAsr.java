package com.memomeeting;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import jakarta.annotation.PreDestroy;

/** DashScope duplex ASR. Audio may only be sent after task-started. */
@Component
public class CloudAsr {
    private final Settings settings;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).factory());
    public CloudAsr(Settings settings,ObjectMapper json){this.settings=settings;this.json=json;}
    public Session connect(String context, Consumer<JsonNode> sentence) {
        return connect(new AiConfigs.Config(settings.asrProvider(),settings.asrUrl(),settings.asrModel(),settings.vocabularyId(),"zh","DEEPGRAM".equals(settings.asrProvider()),settings.asrApiKey()),context,sentence);
    }
    public Session connect(AiConfigs.Config config,String context, Consumer<JsonNode> sentence) {
        if(!config.ready()) throw new IllegalStateException("请先在 AI 设置中配置语音识别 Key");
        boolean deepgram=config.provider().equals("DEEPGRAM"),soniox=config.provider().equals("SONIOX"),tencent=config.provider().equals("TENCENT"),doubao=config.provider().equals("DOUBAO");
        var session=new Session(sentence,deepgram,soniox,tencent,doubao);
        try {
            String url=config.url();
            if(deepgram) url+="?model="+config.model()+"&language="+config.language()+"&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&punctuate=true&endpointing=300"+(config.diarization()?"&diarize_model=latest":"");
            if(tencent)url=TencentAsr.signedUrl(config,session.taskId);
            var builder=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(12));
            if(doubao)builder.header("X-Api-Key",config.apiKey()).header("X-Api-Resource-Id",DoubaoAsr.RESOURCE).header("X-Api-Request-Id",session.taskId).header("X-Api-Connect-Id",session.taskId).header("X-Api-Sequence","-1");
            else if(!tencent)builder.header("Authorization",(deepgram?"Token ":"Bearer ")+config.apiKey());
            session.ws=builder.buildAsync(URI.create(url),session).get(15,TimeUnit.SECONDS);
            if(doubao){
                session.binary(DoubaoAsr.packet(1,false,true,json.writeValueAsBytes(DoubaoAsr.start(json,config,session.taskId,context))));
                // Async mode may have no configuration ACK; test success requires its final response.
                session.ready.complete(null);return session;
            }
            if(tencent){session.ready.get(20,TimeUnit.SECONDS);return session;}
            if(soniox) {
                session.send(sonioxStart(config,context));
                session.ready.complete(null);
                session.keepAlive=heartbeat.scheduleAtFixedRate(()->session.keepAlive(),3,3,TimeUnit.SECONDS);
                return session;
            }
            if(deepgram) {
                session.ready.complete(null);
                session.keepAlive=heartbeat.scheduleAtFixedRate(()->session.keepAlive(),3,3,TimeUnit.SECONDS);
                return session;
            }
            var params=new HashMap<String,Object>();
            params.put("format","pcm");params.put("sample_rate",16000);params.put("heartbeat",true);
            if(config.model().startsWith("fun-asr")) params.put("semantic_punctuation_enabled",true);
            if(!config.vocabularyId().isBlank()) params.put("vocabulary_id",config.vocabularyId());
            Map<String,Object> input=Map.of();
            if(!context.isBlank() && config.model().equals("fun-asr-realtime"))
                input=Map.of("context",List.of(Map.of("role","user","content",List.of(Map.of("type","input_text","text",context)))));
            session.send(Map.of("header",session.header("run-task"),"payload",Map.of("task_group","audio","task","asr","function","recognition","model",config.model(),"parameters",params,"input",input)));
            session.ready.get(20,TimeUnit.SECONDS);
            return session;
        } catch(Exception e){session.abort();throw new IllegalStateException("云端识别连接失败，请检查 Key、地域、模型和服务地址",e);}
    }
    static Map<String,Object> sonioxStart(AiConfigs.Config config,String context){
        var start=new HashMap<String,Object>();
        start.put("model",config.model());start.put("audio_format","pcm_s16le");
        start.put("sample_rate",16000);start.put("num_channels",1);
        if(!config.language().equals("multi"))start.put("language_hints",List.of(config.language()));
        start.put("enable_speaker_diarization",config.diarization());
        start.put("enable_endpoint_detection",false);
        if(!context.isBlank())start.put("context",Map.of("text",context));
        return start;
    }
    public class Session implements WebSocket.Listener, AutoCloseable {
        public final String taskId=UUID.randomUUID().toString();
        public final CompletableFuture<Void> done=new CompletableFuture<>();
        private final CompletableFuture<Void> ready=new CompletableFuture<>();
        private final Consumer<JsonNode> sentence;
        private final boolean deepgram,soniox,tencent,doubao;
        private final DoubaoAsr doubaoTranscript=new DoubaoAsr(json);
        private final ByteArrayOutputStream binaryMessage=new ByteArrayOutputStream();
        private final byte[] pendingAudio=new byte[DoubaoAsr.CHUNK_BYTES];
        private int pendingBytes;
        private final TencentAsr tencentTranscript=new TencentAsr(json);
        private final SonioxTranscript sonioxTranscript=new SonioxTranscript(json);
        private volatile boolean finishRequested;
        private volatile long lastAudio=System.nanoTime();
        private ScheduledFuture<?> keepAlive;
        private final StringBuilder message=new StringBuilder();
        private WebSocket ws;
        private volatile boolean expectedClose;
        Session(Consumer<JsonNode> sentence,boolean deepgram,boolean soniox,boolean tencent,boolean doubao){this.sentence=sentence;this.deepgram=deepgram;this.soniox=soniox;this.tencent=tencent;this.doubao=doubao;}
        Map<String,Object> header(String action){return Map.of("action",action,"task_id",taskId,"streaming","duplex");}
        void send(Object obj) throws Exception {ws.sendText(json.writeValueAsString(obj),true).get(8,TimeUnit.SECONDS);}
        void binary(byte[] bytes) throws Exception {ws.sendBinary(ByteBuffer.wrap(bytes),true).get(8,TimeUnit.SECONDS);}
        public synchronized void audio(byte[] bytes) throws Exception {
            if(done.isDone()) {done.join();throw new IllegalStateException("识别任务已结束");}
            if(finishRequested)throw new IllegalStateException("识别任务正在结束");
            if(doubao){
                int offset=0;
                while(offset<bytes.length){
                    // Retain the last 200 ms for a nonempty terminal packet.
                    if(pendingBytes==pendingAudio.length){binary(DoubaoAsr.packet(2,false,false,pendingAudio));pendingBytes=0;}
                    int size=Math.min(bytes.length-offset,pendingAudio.length-pendingBytes);
                    System.arraycopy(bytes,offset,pendingAudio,pendingBytes,size);pendingBytes+=size;offset+=size;
                }
            }else binary(bytes);
            lastAudio=System.nanoTime();
        }
        public synchronized void finish() throws Exception {
            if(finishRequested)return;
            finishRequested=true;
            if(doubao){binary(DoubaoAsr.packet(2,true,false,Arrays.copyOf(pendingAudio,pendingBytes)));pendingBytes=0;return;}
            if(tencent){send(Map.of("type","end"));return;}
            if(soniox){ws.sendText("",true).get(8,TimeUnit.SECONDS);return;}
            send(deepgram?Map.of("type","CloseStream"):Map.of("header",header("finish-task"),"payload",Map.of("input",Map.of())));
        }
        private void keepAlive(){if(!finishRequested && !done.isDone() && System.nanoTime()-lastAudio>TimeUnit.SECONDS.toNanos(3))ws.sendText(soniox?"{\"type\":\"keepalive\"}":"{\"type\":\"KeepAlive\"}",true).whenComplete((v,e)->{if(e!=null)fail(e);});}
        @Override public void onOpen(WebSocket webSocket){webSocket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket webSocket,CharSequence data,boolean last){
            if(doubao){fail(new IllegalStateException("豆包返回了非二进制响应"));webSocket.request(1);return CompletableFuture.completedFuture(null);}
            message.append(data);
            if(last) {
                try {
                    var node=json.readTree(message.toString());message.setLength(0);
                    if(tencent) {
                        if(!node.path("code").canConvertToInt() || node.path("code").asInt()!=0)fail(new TencentAsr.Rejected(node.path("code").asInt(-1)));
                        else {
                            ready.complete(null);tencentTranscript.accept(node).forEach(sentence);
                            if(node.path("final").asInt()==1){expectedClose=true;done.complete(null);}
                        }
                        webSocket.request(1);return CompletableFuture.completedFuture(null);
                    }
                    if(soniox) {
                        if(node.hasNonNull("error_code"))fail(new IllegalStateException("Soniox 拒绝识别任务（"+node.path("error_code").asInt()+"）"));
                        else {
                            sonioxTranscript.accept(node).forEach(sentence);
                            if(node.path("finished").asBoolean()){expectedClose=true;done.complete(null);}
                        }
                        webSocket.request(1);return CompletableFuture.completedFuture(null);
                    }
                    if(deepgram) {
                        if(node.path("type").asText().equals("Results")) normalizeDeepgram(json,node).forEach(sentence);
                        else if(node.path("type").asText().equals("Error"))fail(new IllegalStateException("云端拒绝识别任务"));
                        webSocket.request(1);return CompletableFuture.completedFuture(null);
                    }
                    switch(node.path("header").path("event").asText()) {
                        case "task-started" -> ready.complete(null);
                        case "result-generated" -> {
                            var value=node.path("payload").path("output").path("sentence");
                            if(!value.isMissingNode() && !value.path("heartbeat").asBoolean()) sentence.accept(value);
                        }
                        case "task-finished" -> {expectedClose=true;done.complete(null);}
                        case "task-failed" -> fail(new IllegalStateException("云端拒绝识别任务："+node.path("header").path("error_code").asText("UNKNOWN")));
                        default -> { }
                    }
                } catch(Exception e){fail(e);}
            }
            webSocket.request(1);return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onBinary(WebSocket webSocket,ByteBuffer data,boolean last){
            try {
                if(!doubao)throw new IllegalStateException("云端返回了非预期的二进制响应");
                if(binaryMessage.size()+data.remaining()>DoubaoAsr.MAX_FRAME)throw new IllegalStateException("豆包响应过大");
                byte[] fragment=new byte[data.remaining()];data.get(fragment);binaryMessage.write(fragment);
                if(last){
                    var response=DoubaoAsr.decode(json,binaryMessage.toByteArray());binaryMessage.reset();
                    doubaoTranscript.accept(response).forEach(sentence);
                    if(response.terminal()){
                        if(!finishRequested)throw new IllegalStateException("豆包提前结束识别任务");
                        expectedClose=true;done.complete(null);
                    }
                }
            }catch(Exception error){binaryMessage.reset();fail(error);}
            webSocket.request(1);return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket,int status,String reason){
            if(keepAlive!=null)keepAlive.cancel(false);
            if(deepgram && finishRequested && status==1000){expectedClose=true;done.complete(null);}
            else if(!expectedClose) fail(new IllegalStateException("云端识别连接意外断开"));
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket,Throwable error){fail(error);}
        private void fail(Throwable e){ready.completeExceptionally(e);done.completeExceptionally(e);}
        public void abort(){expectedClose=true;if(keepAlive!=null)keepAlive.cancel(false);if(ws!=null) ws.abort();}
        @Override public void close(){abort();}
    }
    /** A final result may contain multiple speaker turns. Preserve each turn independently. */
    static List<JsonNode> normalizeDeepgram(ObjectMapper json,JsonNode message){
        var alternative=message.path("channel").path("alternatives").path(0);
        String transcript=alternative.path("transcript").asText();if(transcript.isBlank())return List.of();
        long begin=Math.round(message.path("start").asDouble()*1000),end=begin+Math.round(message.path("duration").asDouble()*1000);
        boolean confirmed=message.path("is_final").asBoolean();
        var words=alternative.path("words");var result=new ArrayList<JsonNode>();
        if(!confirmed || !words.isArray() || words.isEmpty()) {
            var node=json.createObjectNode().put("sentence_id",begin+":0").put("begin_time",begin).put("end_time",end).put("text",transcript).put("sentence_end",confirmed);result.add(node);return result;
        }
        String speaker=null;long start=0,stop=0;int part=0;var text=new StringBuilder();
        for(var word:words) {
            String next=word.hasNonNull("speaker")?word.get("speaker").asText():null;
            if(!text.isEmpty() && !Objects.equals(speaker,next)) {
                var node=json.createObjectNode().put("sentence_id",begin+":"+part++).put("begin_time",start).put("end_time",stop).put("text",text.toString()).put("sentence_end",true);
                if(speaker!=null)node.put("speaker_id",speaker);result.add(node);text.setLength(0);
            }
            String token=word.path("punctuated_word").asText(word.path("word").asText());
            if(text.isEmpty())start=Math.round(word.path("start").asDouble()*1000);
            else if(!token.isEmpty() && token.charAt(0)<128 && text.charAt(text.length()-1)<128)text.append(' ');
            text.append(token);stop=Math.round(word.path("end").asDouble()*1000);speaker=next;
        }
        if(!text.isEmpty()) {
            var node=json.createObjectNode().put("sentence_id",begin+":"+part).put("begin_time",start).put("end_time",stop).put("text",text.toString()).put("sentence_end",true);
            if(speaker!=null)node.put("speaker_id",speaker);result.add(node);
        }
        return result;
    }
    @PreDestroy public void shutdown(){heartbeat.shutdownNow();}
}
