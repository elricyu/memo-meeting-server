package com.memomeeting;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:admintests;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class AdminTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("memo.data-dir",()->System.getProperty("java.io.tmpdir")+"/memo-admin-"+UUID.randomUUID());}
    @Autowired MockMvc mvc;
    @Autowired Accounts accounts;
    @Autowired Store store;
    @Autowired MeetingService meetings;
    @Autowired AiConfigs configs;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @AfterEach void clearContext(){SecurityContextHolder.clearContext();}
    Accounts.User user(String role){var user=accounts.register("ad_"+UUID.randomUUID().toString().replace("-", "").substring(0,12),"角色测试","explicit-test-password");db.update("UPDATE users SET role=? WHERE id=?",role,user.id());return accounts.get(user.id());}
    MockHttpSession session(Accounts.User user,String device){var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(new Identity(user.id(),user.username(),user.displayName(),device),null,List.of()));var session=new MockHttpSession();session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);return session;}
    @Test void localSettingsRequireAdministrator() throws Exception {
        mvc.perform(get("/api/v1/admin/local-speech").session(session(user("USER"),null))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/local-speech").session(session(user("ADMIN"),null))).andExpect(status().isOk()).andExpect(jsonPath("$.automatic").value(true)).andExpect(jsonPath("$.token").doesNotExist());
    }
    @Test void meetingListReportsUsableTranscriptCountsWithoutExposingOtherOwners() throws Exception {
        var owner=user("USER");var other=user("USER");
        var identity=new Identity(owner.id(),owner.username(),owner.displayName(),null);
        var meeting=store.createOwned("已完成会议","","fixture","WEB",null,identity);
        store.state(meeting.id(),"COMPLETED","");
        var login=session(owner,null);
        mvc.perform(get("/api/v1/meetings").session(login)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(meeting.id())).andExpect(jsonPath("$[0].transcriptCount").value(0));
        store.segment(meeting.id(),new Store.Segment("valid",0,100,"有效文字"));
        store.segment(meeting.id(),new Store.Segment("blank",100,200,"   "));
        store.segment(meeting.id(),new Store.Segment("excluded",200,300,"已排除文字"));
        db.update("UPDATE segments SET excluded=TRUE WHERE meeting_id=? AND segment_id='excluded'",meeting.id());
        mvc.perform(get("/api/v1/meetings").session(login)).andExpect(status().isOk()).andExpect(jsonPath("$[0].transcriptCount").value(1));
        mvc.perform(get("/api/v1/meetings").session(session(other,null))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void onlyAdministratorCanReadGlobalConfigurationIncludingLegacyPath() throws Exception {
        var normal=user("USER");var admin=user("ADMIN");
        mvc.perform(get("/api/v1/admin/ai")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/ai").session(session(normal,null))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ai/settings").session(session(normal,null))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ai").session(session(admin,"test-device"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ai").session(session(admin,null))).andExpect(status().isOk());
    }
    @Test void ordinaryReadinessNeverDisclosesConfigurationCredentials() throws Exception {
        var admin=user("ADMIN");String key="secret-admin-only-test-key";
        configs.save(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","fun-asr-realtime","","zh",false,false,key));
        var normal=user("USER");String body=mvc.perform(get("/api/v1/config").session(session(normal,null))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(json.readTree(body).path("asrReady").asBoolean());assertFalse(body.contains(key));assertFalse(body.contains("keySet"));
    }
    @Test void staleSessionsCannotKeepAdminPrivilegesAfterDatabaseRoleChanges() throws Exception {
        var admin=user("ADMIN");var session=session(admin,null);
        mvc.perform(get("/api/v1/admin/ai").session(session)).andExpect(status().isOk());
        db.update("UPDATE users SET role='USER' WHERE id=?",admin.id());
        mvc.perform(get("/api/v1/admin/ai").session(session)).andExpect(status().isForbidden());
    }
    @Test void validCsrfDoesNotGiveOrdinaryUsersWriteOrTestPrivileges() throws Exception {
        var normal=user("USER");var session=session(normal,null);
        var csrf=mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getContentAsString();
        String token=json.readTree(csrf).path("token").asText();
        String input=json.writeValueAsString(new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","fun-asr-realtime","","zh",false,false,"test-only-key"));
        for(String path:List.of("/api/v1/admin/ai/asr","/api/v1/admin/ai/asr/test","/api/v1/ai/settings/asr","/api/v1/ai/settings/asr/test"))
            mvc.perform(post(path).session(session).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN",token)).header("X-XSRF-TOKEN",token).contentType("application/json").content(input)).andExpect(status().isForbidden());
    }
    @Test void failedCloudProbeCannotReportSuccessOrExposeProviderSecrets(){
        var admin=user("ADMIN");var access=org.mockito.Mockito.mock(Access.class);
        org.mockito.Mockito.when(access.admin()).thenReturn(new Identity(admin.id(),admin.username(),admin.displayName(),null));
        var cloud=org.mockito.Mockito.mock(CloudAsr.class);
        org.mockito.Mockito.when(cloud.connect(org.mockito.ArgumentMatchers.any(AiConfigs.Config.class),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("provider diagnostic contains secret-not-for-client"));
        var probe=new AdminAiApi(access,configs,cloud,json,new RateLimits());
        var result=probe.test("asr",new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","fun-asr-realtime","","zh",false,false,"test-only-key"));
        assertFalse(result.ok());assertFalse(result.message().contains("secret-not-for-client"));assertEquals("CONNECTION_FAILED",result.code());
    }
    @Test void lostMasterKeyCannotSilentlyReplaceExistingCredentials() throws Exception {
        var isolated=org.mockito.Mockito.mock(JdbcTemplate.class);
        var directory=java.nio.file.Files.createTempDirectory("memo-lost-master-");
        var settings=new Settings(directory.toString(),"","","","","","","",new String[0],10);
        org.mockito.Mockito.when(isolated.queryForObject(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(1L);
        assertThrows(IllegalStateException.class,()->new SecretBox(settings,isolated));
        assertFalse(java.nio.file.Files.exists(directory.resolve("ai-master.key")));
        java.nio.file.Files.delete(directory);
    }
    @Test void globalCredentialsAreEncryptedInheritedAndBoundToTheGlobalNamespace() throws Exception {
        var admin=user("ADMIN");var normal=user("USER");String key="encrypted-global-test-key";
        var view=configs.save(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","fun-asr-realtime","","zh",false,false,key));
        String cipher=db.queryForObject("SELECT key_cipher FROM global_ai_configs WHERE kind='asr'",String.class);
        assertFalse(cipher.contains(key));assertFalse(json.writeValueAsString(view).contains(key));
        assertFalse(json.writeValueAsString(configs.resolve(normal.id(),"asr")).contains(key));
        assertEquals(key,configs.resolve(normal.id(),"asr").apiKey());
        configs.save(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","fun-asr-realtime","","zh",false,false,""));
        assertEquals(key,configs.resolve(normal.id(),"asr").apiKey());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->configs.candidate(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DASHSCOPE_INTL","fun-asr-realtime","","zh",false,false,"")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->configs.candidate(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DASHSCOPE_EVIL","fun-asr-realtime","","zh",false,false,"key","test")));
    }
    @Test void summaryModelDisplayCasingIsCorrectedForDraftsAndExistingSavedSettings() {
        var admin=user("ADMIN");
        var input=new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","Qwen3.7-Flash","","zh",false,false,"fixture-llm-key");
        assertEquals("qwen3.7-flash",configs.candidate(admin.id(),"llm",input).model());
        configs.save(admin.id(),"llm",input);
        db.update("UPDATE global_ai_configs SET model='Qwen3.7-Flash' WHERE kind='llm'");
        assertEquals("qwen3.7-flash",configs.resolve(admin.id(),"llm").model());
        assertEquals("qwen3.7-flash",configs.view(admin.id(),"llm").model());
    }
    @Test void switchingToSonioxPreservesExistingSharedBailianSummaryCredentials(){
        var admin=user("ADMIN");
        configs.save(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","fun-asr-realtime","","zh",false,false,"bailian-previous-key"));
        configs.save(admin.id(),"llm",new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","qwen-plus","","zh",false,true,""));
        configs.save(admin.id(),"asr",new AiConfigs.Input("GLOBAL","SONIOX","stt-rt-v5","","zh",true,false,"soniox-new-key"));
        assertEquals("bailian-previous-key",configs.resolve(admin.id(),"llm").apiKey());
        assertEquals("qwen-plus",configs.resolve(admin.id(),"llm").model());
        assertFalse(configs.view(admin.id(),"llm").reuseAsrKey());
        assertTrue(configs.view(admin.id(),"asr").supportsDiarization());
        assertEquals("soniox-new-key",configs.resolve(admin.id(),"asr").apiKey());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->configs.candidate(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DASHSCOPE_CN","fun-asr-realtime","","zh",false,false,"")));
    }
    @Test void speakerCanBeRenamedDuringRecordingWithoutReleasingRecordingOwnership() throws Exception {
        var owner=user("USER");var identity=new Identity(owner.id(),owner.username(),owner.displayName(),null);
        var meeting=store.createOwned("实时改名","","token","WEB",null,identity);
        store.segment(meeting.id(),new Store.Segment("segment-a",0,100,"第一句","speaker-1"));
        store.state(meeting.id(),"RECORDING","");meetings.reserve(meeting.id());
        try {
            var login=session(owner,null);
            String token=json.readTree(mvc.perform(get("/api/v1/auth/csrf").session(login)).andReturn().getResponse().getContentAsString()).path("token").asText();
            mvc.perform(post("/api/v1/meetings/"+meeting.id()+"/speakers/speaker-1").session(login).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN",token)).header("X-XSRF-TOKEN",token).contentType("application/json").content("{\"name\":\"张三\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.speakers[0].name").value("张三"));
            store.segment(meeting.id(),new Store.Segment("segment-b",100,200,"后续发言","speaker-1"));
            assertEquals("张三",store.speakers(meeting.id()).getFirst().name());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->meetings.reserve(meeting.id()));
        } finally {meetings.release(meeting.id());}
    }
    @Test void tencentCredentialPairIsEncryptedAndNeverReturnedOrReusedAcrossAppIds() throws Exception {
        var admin=user("ADMIN");
        var input=new AiConfigs.Input("GLOBAL","TENCENT","16k_zh_en_speaker_2.0","","zh",true,false,"","","1250000000","fixtureSecretId123","fixtureSecretKey123");
        var view=configs.save(admin.id(),"asr",input);String serialized=json.writeValueAsString(view);
        assertEquals("1250000000",view.appId());assertTrue(view.supportsDiarization());
        assertFalse(serialized.contains("fixtureSecret"));assertFalse(db.queryForObject("SELECT key_cipher FROM global_ai_configs WHERE kind='asr'",String.class).contains("fixtureSecret"));
        var blank=new AiConfigs.Input("GLOBAL","TENCENT","16k_zh_en_speaker_2.0","","zh",true,false,"","","1250000000","","");
        assertEquals(configs.resolve(admin.id(),"asr").apiKey(),configs.candidate(admin.id(),"asr",blank).apiKey());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->configs.candidate(admin.id(),"asr",new AiConfigs.Input("GLOBAL","TENCENT","16k_zh_en_speaker_2.0","","zh",true,false,"","","1250000001","","")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->configs.candidate(admin.id(),"asr",new AiConfigs.Input("GLOBAL","TENCENT","16k_zh_en_speaker_2.0","","zh",true,false,"","","1250000000","replacementSecretId","")));
    }
    @Test void tencentProviderFailureShowsSpecificActionWithoutLeakingExternalDiagnostic(){
        var admin=user("ADMIN");var access=org.mockito.Mockito.mock(Access.class);
        org.mockito.Mockito.when(access.admin()).thenReturn(new Identity(admin.id(),admin.username(),admin.displayName(),null));
        var cloud=org.mockito.Mockito.mock(CloudAsr.class);
        org.mockito.Mockito.when(cloud.connect(org.mockito.ArgumentMatchers.any(AiConfigs.Config.class),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("wrapper contains secret-not-for-client",new java.util.concurrent.ExecutionException(new TencentAsr.Rejected(4004))));
        var probe=new AdminAiApi(access,configs,cloud,json,new RateLimits());
        var input=new AiConfigs.Input("GLOBAL","TENCENT","16k_zh_en_speaker_2.0","","zh",true,false,"","","1250000000","fixtureSecretId123","fixtureSecretKey123");
        var result=probe.test("asr",input);
        assertFalse(result.ok());assertEquals("TENCENT_QUOTA_EXHAUSTED",result.code());
        assertTrue(result.message().contains("4004"));assertTrue(result.message().contains("后付费"));assertFalse(result.message().contains("secret-not-for-client"));
        assertEquals("TENCENT_AUTH_FAILED",new TencentAsr.Rejected(4002).resultCode());
        assertEquals("TENCENT_SERVICE_NOT_ENABLED",new TencentAsr.Rejected(4003).resultCode());
    }
    @Test void doubaoModelCapabilitiesDetermineDiarizationAndSecretsRemainEncrypted() throws Exception {
        var admin=user("ADMIN");
        var view=configs.save(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DOUBAO",AsrModels.DOUBAO_SENTENCE,"","zh",true,false,"doubao-test-secret"));
        assertTrue(view.supportsDiarization());assertEquals("STREAMING_SENTENCE",view.capabilities().speakerMode());
        assertTrue(configs.resolve(admin.id(),"asr").url().endsWith("bigmodel_nostream"));
        assertFalse(json.writeValueAsString(view).contains("doubao-test-secret"));assertFalse(db.queryForObject("SELECT key_cipher FROM global_ai_configs WHERE kind='asr'",String.class).contains("doubao-test-secret"));
        var streaming=new AiConfigs.Input("GLOBAL","DOUBAO",AsrModels.DOUBAO_STREAM,"","zh",false,false,"");
        view=configs.save(admin.id(),"asr",streaming);assertFalse(view.supportsDiarization());assertTrue(view.capabilities().partialResults());
        assertTrue(configs.resolve(admin.id(),"asr").url().endsWith("bigmodel_async"));assertEquals("doubao-test-secret",configs.resolve(admin.id(),"asr").apiKey());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->configs.candidate(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DOUBAO",AsrModels.DOUBAO_STREAM,"","zh",true,false,"key")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->configs.candidate(admin.id(),"asr",new AiConfigs.Input("GLOBAL","DOUBAO","unverified-model","","zh",false,false,"key")));
        mvc.perform(get("/api/v1/admin/ai/models").session(session(admin,null))).andExpect(status().isOk()).andExpect(jsonPath("$[0].provider").value("DOUBAO"));
        mvc.perform(get("/api/v1/admin/ai/models").session(session(user("USER"),null))).andExpect(status().isForbidden());
    }

}
