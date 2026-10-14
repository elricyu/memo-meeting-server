package com.memomeeting;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.concurrent.ConcurrentHashMap;

/** Only abuse counters are ephemeral; accounts, ownership and credentials are persistent. */
@Component
public class RateLimits {
    private record Window(long until,int count) {}
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    public void check(String key,int max){
        long now=System.currentTimeMillis();
        var result=windows.compute(key,(k,v)->v==null || v.until()<now?new Window(now+60000,1):new Window(v.until(),v.count()+1));
        if(windows.size()>10000) windows.entrySet().removeIf(e->e.getValue().until()<now);
        if(result.count()>max)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"操作过于频繁，请一分钟后重试");
    }
}
