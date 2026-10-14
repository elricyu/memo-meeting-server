package com.memomeeting;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.*;
import java.util.*;
import java.util.function.Supplier;

/** Bounded fan-out. A slow viewing device must never block microphone or ASR. */
@Component
public class Events {
    private record Event(String type,Object data) {}
    private final Map<String,CopyOnWriteArrayList<Client>> channels=new ConcurrentHashMap<>();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private class Client {
        final SseEmitter emitter=new SseEmitter(0L);
        final BlockingQueue<Event> queue=new ArrayBlockingQueue<>(8);
        final String channel;
        final String actor;
        volatile boolean closed;
        Future<?> worker;
        Client(String channel,String actor){this.channel=channel;this.actor=actor;}
        void close(){
            closed=true;
            var clients=channels.get(channel);if(clients!=null) clients.remove(this);
            if(worker!=null) worker.cancel(true);
        }
        void enqueue(String type,Object data){
            if(closed) return;
            // Snapshots are complete replacements; pending snapshots can be coalesced.
            queue.removeIf(event->event.type().equals(type));
            if(!queue.offer(new Event(type,data)) && !type.equals("heartbeat")) {
                close();emitter.complete();
            }
        }
        void run(){
            try {
                while(!closed){var event=queue.take();emitter.send(SseEmitter.event().name(event.type()).data(event.data()));}
            } catch(Exception e){close();emitter.complete();}
        }
    }
    public synchronized SseEmitter subscribe(String channel,Supplier<Object> initial){
        return subscribe(channel,initial,"");
    }
    public synchronized SseEmitter subscribe(String channel,Supplier<Object> initial,String actor){
        var client=new Client(channel,actor);
        client.emitter.onCompletion(client::close);client.emitter.onTimeout(client::close);client.emitter.onError(e->client.close());
        // Before controller return, SseEmitter buffers this initial snapshot without network I/O.
        try{client.emitter.send(SseEmitter.event().name("snapshot").data(initial.get()));}
        catch(java.io.IOException e){client.close();client.emitter.complete();return client.emitter;}
        channels.computeIfAbsent(channel,k->new CopyOnWriteArrayList<>()).add(client);
        client.worker=workers.submit(client::run);return client.emitter;
    }
    public synchronized void publish(String channel,String type,Object data){
        for(var client:channels.getOrDefault(channel,new CopyOnWriteArrayList<>())) client.enqueue(type,data);
    }
    public void closeActor(String actor){channels.values().forEach(list->list.forEach(client->{if(client.actor.equals(actor)){client.close();client.emitter.complete();}}));}
    @Scheduled(fixedDelay=15000) public void heartbeat(){channels.keySet().forEach(k->publish(k,"heartbeat",Map.of("time",System.currentTimeMillis())));}
    @PreDestroy public void close(){channels.values().forEach(list->list.forEach(client->{client.close();client.emitter.complete();}));workers.shutdownNow();}
}
