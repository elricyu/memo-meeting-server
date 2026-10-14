package com.memomeeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:devicetests;DB_CLOSE_DELAY=-1"})
class DeviceTests {
    static final String data=System.getProperty("java.io.tmpdir")+"/memo-device-test-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("memo.data-dir",()->data);}
    @Autowired Devices devices;
    @Autowired Accounts accounts;
    @Autowired JdbcTemplate db;
    @Autowired Store store;
    @Autowired Access access;
    String token(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
    @SuppressWarnings("unchecked") @Test void expiryAndCredentialRevocationArePersistent(){
        String id=UUID.randomUUID().toString(),secret=token();
        var pair=(Map<String,Object>)devices.initiate(id,"02:11:22:33:44:55","眼镜","RV1106B",secret,UUID.randomUUID().toString());
        assertNull(devices.authenticate(secret));
        db.update("UPDATE pairings SET expires_at=1 WHERE id=?",pair.get("pairingId"));
        assertEquals("EXPIRED",((Map<String,Object>)devices.poll((String)pair.get("pairingId"),secret)).get("status"));
        assertThrows(ResponseStatusException.class,()->devices.bind((String)pair.get("pairingCode"),1));
        var next=(Map<String,Object>)devices.initiate(id,"02:11:22:33:44:55","眼镜","RV1106B",token(),UUID.randomUUID().toString());
        assertNotEquals(pair.get("deviceId"),next.get("deviceId"));
        assertEquals("REVOKED",db.queryForObject("SELECT status FROM devices WHERE id=?",String.class,pair.get("deviceId")));
    }
    @SuppressWarnings("unchecked") @Test void bindingStoresBothIdentifiersAndProtectsOtherUsers(){
        var user=accounts.register("u"+token().substring(0,10),"测试用户","strong-test-password");
        String client=UUID.randomUUID().toString(),secret=token(),mac="02:66:77:88:99:AA";
        var pair=(Map<String,Object>)devices.initiate(client,mac,"眼镜","RV1106B",secret,UUID.randomUUID().toString());
        var bound=devices.bind((String)pair.get("pairingCode"),user.id());
        assertEquals(client,bound.clientId());assertEquals(mac,bound.mac());assertNotNull(devices.authenticate(secret));
        assertThrows(ResponseStatusException.class,()->devices.initiate(UUID.randomUUID().toString(),mac,"另一副","RV1106B",token(),UUID.randomUUID().toString()));
        assertThrows(ResponseStatusException.class,()->devices.unbind(bound.id(),user.id()+999));
        devices.unbind(bound.id(),user.id());assertNull(devices.authenticate(secret));
        assertEquals(client,db.queryForObject("SELECT client_id FROM devices WHERE id=?",String.class,bound.id()));
        assertEquals(mac,db.queryForObject("SELECT mac FROM devices WHERE id=?",String.class,bound.id()));
    }
    @Test void ownerCannotBeChosenByClientAndUnownedLegacyRecordsStayPrivate(){
        var user=accounts.register("u"+token().substring(0,10),"归属用户","strong-test-password");
        var identity=new Identity(user.id(),user.username(),user.displayName(),null);
        var meeting=store.createOwned("会议","",token(),"WEB",UUID.randomUUID().toString(),identity);
        access.meeting(meeting.id(),identity);
        assertEquals(user.id(),meeting.ownerId());
        assertThrows(ResponseStatusException.class,()->access.meeting(meeting.id(),new Identity(user.id()+1,"other","other",null)));
        var legacy=store.create("未分配的旧记录","",token());
        assertThrows(ResponseStatusException.class,()->access.meeting(legacy.id(),identity));
    }
}
