package com.memomeeting;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memo")
public record Settings(String dataDir, String asrApiKey, String asrUrl, String asrModel,
                       String vocabularyId, String llmApiKey, String llmUrl, String llmModel,
                       String[] allowedOrigins, long maxSeconds,String asrProvider) {
    public Settings(String dataDir,String asrApiKey,String asrUrl,String asrModel,String vocabularyId,String llmApiKey,String llmUrl,String llmModel,String[] allowedOrigins,long maxSeconds){this(dataDir,asrApiKey,asrUrl,asrModel,vocabularyId,llmApiKey,llmUrl,llmModel,allowedOrigins,maxSeconds,"DASHSCOPE");}
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Settings {
        if (llmApiKey == null || llmApiKey.isBlank()) llmApiKey = asrApiKey;
    }
    public boolean asrReady() { return asrApiKey != null && !asrApiKey.isBlank(); }
    public boolean llmReady() { return llmApiKey != null && !llmApiKey.isBlank(); }
}
