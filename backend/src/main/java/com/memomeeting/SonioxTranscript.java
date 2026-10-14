package com.memomeeting;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Final tokens arrive once; provisional tokens replace the preceding provisional tail. */
final class SonioxTranscript {
    private final ObjectMapper json;
    private long turn, begin, end;
    private String speaker;
    private final StringBuilder confirmed=new StringBuilder();
    SonioxTranscript(ObjectMapper json){this.json=json;}
    List<JsonNode> accept(JsonNode response){
        var result=new ArrayList<JsonNode>();
        var provisional=new StringBuilder();
        boolean changed=false;
        for(var token:response.path("tokens")) {
            if(token.path("translation_status").asText().equals("translation"))continue;
            String text=token.path("text").asText();
            if(text.equals("<end>") || text.equals("<fin>"))continue;
            if(!token.path("is_final").asBoolean()){provisional.append(text);continue;}
            if(text.isEmpty())continue;
            String next=token.hasNonNull("speaker")?token.get("speaker").asText():null;
            long start=token.path("start_ms").asLong(end),stop=token.path("end_ms").asLong(start);
            if(!confirmed.isEmpty() && (!Objects.equals(speaker,next) || start-end>1500 || confirmed.length()>800)) {
                if(changed)result.add(snapshot());
                confirmed.setLength(0);turn++;changed=false;
            }
            if(confirmed.isEmpty())begin=start;
            confirmed.append(text);speaker=next;end=Math.max(start,stop);changed=true;
        }
        if(changed)result.add(snapshot());
        // An empty draft is significant: the prior provisional tail has been finalized.
        result.add(json.createObjectNode().put("text",provisional.toString()).put("sentence_end",false));
        return result;
    }
    private JsonNode snapshot(){
        var node=json.createObjectNode().put("sentence_id","turn-"+turn).put("begin_time",begin)
            .put("end_time",end).put("text",confirmed.toString()).put("sentence_end",true);
        if(speaker!=null)node.put("speaker_id",speaker);
        return node;
    }
}
