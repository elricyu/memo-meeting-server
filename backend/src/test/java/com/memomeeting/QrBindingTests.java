package com.memomeeting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:qrtests;DB_CLOSE_DELAY=-1"})
class QrBindingTests {
 @Autowired WebBindings web;@Autowired BindingQr qr;@Autowired Accounts accounts;@Autowired Devices devices;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("memo.data-dir",()->System.getProperty("java.io.tmpdir")+"/memo-qr-"+UUID.randomUUID());}
 long user(){return accounts.register("qr_"+UUID.randomUUID().toString().substring(0,8),"扫码测试","explicit-test-password").id();}
 DeviceApi.Pair pair(){return new DeviceApi.Pair("fixture-"+UUID.randomUUID(),String.format("02:11:22:%02X:%02X:%02X",new Random().nextInt(256),new Random().nextInt(256),new Random().nextInt(256)),"扫码眼镜","RV1106B",UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),UUID.randomUUID().toString());}
 @Test void qrClaimCannotCreateIdentityUntilOwnerConfirmsAndCannotBeStolen() throws Exception {
  long owner=user(),other=user();var request=web.create(owner);var input=pair();
  assertNotNull(request.qrToken());assertEquals(64,request.qrToken().length());assertNull(web.read(request.id(),owner).qrToken());
  assertThrows(ResponseStatusException.class,()->web.claim(request.id(),"0".repeat(64),input));
  web.claim(request.id(),request.qrToken(),input);assertNull(devices.authenticate(input.deviceToken()));
  assertThrows(ResponseStatusException.class,()->web.confirm(request.id(),other));
  var snapshot=web.read(request.id(),owner);assertEquals("CLAIMED",snapshot.status());
  String legacy=db.queryForObject("SELECT code FROM pairings WHERE device_id=?",String.class,snapshot.device().id());
  assertThrows(ResponseStatusException.class,()->devices.bind(legacy,other));
  assertNotNull(web.recover(input.clientRequestId(),input.deviceToken()));
  web.confirm(request.id(),owner);assertEquals(owner,devices.authenticate(input.deviceToken()).userId());
  assertThrows(ResponseStatusException.class,()->web.claim(request.id(),request.qrToken(),input));
 }
 @Test void cancelledAndExpiredCodesCannotBindAndReleaseHardware(){
  long owner=user();var request=web.create(owner);var input=pair();web.claim(request.id(),request.qrToken(),input);web.cancel(request.id(),owner);
  assertEquals("CANCELLED",web.read(request.id(),owner).status());assertThrows(ResponseStatusException.class,()->web.confirm(request.id(),owner));assertNull(devices.authenticate(input.deviceToken()));
  var expired=web.create(owner);db.update("UPDATE web_bindings SET expires_at=0 WHERE id=?",expired.id());assertThrows(ResponseStatusException.class,()->web.claim(expired.id(),expired.qrToken(),pair()));
 }
 @Test void qrImageDecodesDeterministicallyAndRejectsNonBindingQr() throws Exception {
  var request=web.create(user());String payload=json.writeValueAsString(Map.of("v",1,"requestId",request.id(),"token",request.qrToken()));
  var matrix=new QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,480,480);var image=new BufferedImage(480,480,BufferedImage.TYPE_INT_RGB);
  for(int y=0;y<480;y++)for(int x=0;x<480;x++)image.setRGB(x,y,matrix.get(x,y)?0:0xFFFFFF);
  var bytes=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",bytes);var decoded=qr.decode(bytes.toByteArray());assertEquals(request.id(),decoded.requestId());assertEquals(request.qrToken(),decoded.token());
  assertThrows(ResponseStatusException.class,()->qr.decode(new byte[20]));
 }
 @Test void hardwareCheckDistinguishesMissingCredentialWithoutGrantingOwnership() throws Exception {
  long owner=user();var input=pair();var request=web.create(owner);
  assertEquals("UNBOUND",((Map<?,?>)web.check(input.clientId(),input.mac(),null)).get("state"));
  web.claim(request.id(),request.qrToken(),input);
  assertEquals("PENDING",((Map<?,?>)web.check(input.clientId(),input.mac(),input.deviceToken())).get("state"));
  assertEquals("REBIND_REQUIRED",((Map<?,?>)web.check(input.clientId(),input.mac(),null)).get("state"));
  var device=(Devices.Device)web.confirm(request.id(),owner);
  assertEquals("BOUND",((Map<?,?>)web.check(input.clientId(),input.mac(),input.deviceToken())).get("state"));
  var missing=(Map<?,?>)web.check(input.clientId(),input.mac(),"0".repeat(64));
  assertEquals("REBIND_REQUIRED",missing.get("state"));assertEquals(Set.of("state","message"),missing.keySet());
  assertEquals("REBIND_REQUIRED",((Map<?,?>)web.check("forged-client",input.mac(),input.deviceToken())).get("state"));
  assertNull(devices.authenticate("0".repeat(64)));
  devices.unbind(device.id(),owner);
  assertEquals("UNBOUND",((Map<?,?>)web.check(input.clientId(),input.mac(),input.deviceToken())).get("state"));
 }
}
