package com.memomeeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:codebinding;DB_CLOSE_DELAY=-1","memo.data-dir=${java.io.tmpdir}/memo-codebinding-tests"})
class CodeBindingTests {
 @Autowired Devices devices;
 @Autowired Accounts accounts;
 @Autowired JdbcTemplate db;
 String token(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
 @SuppressWarnings("unchecked") Map<String,Object> pair(String cid,String mac,String secret,String request){
  return (Map<String,Object>)devices.initiate(cid,mac,"测试眼镜","RV1106B",secret,request);
 }
 @Test void expiryRenewsSameRequestAndImmediatelyInvalidatesPreviousCode(){
  String cid=UUID.randomUUID().toString(),request=UUID.randomUUID().toString(),secret=token(),mac="02:10:20:30:40:50";
  var first=pair(cid,mac,secret,request);
  assertTrue(((String)first.get("pairingCode")).matches("[0-9]{4}"));
  assertEquals(first,pair(cid,mac,secret,request));
  db.update("UPDATE pairings SET expires_at=0 WHERE id=?",first.get("pairingId"));
  assertThrows(ResponseStatusException.class,()->devices.preview((String)first.get("pairingCode")));
  var renewed=pair(cid,mac,secret,request);
  assertEquals(first.get("pairingId"),renewed.get("pairingId"));
  assertNotEquals(first.get("pairingCode"),renewed.get("pairingCode"));
  assertThrows(ResponseStatusException.class,()->devices.preview((String)first.get("pairingCode")));
  assertEquals(renewed,pair(cid,mac,secret,request));
  assertNull(devices.authenticate(secret));
 }
 @Test void anotherDeviceStartingDoesNotPreventExpiredCodeRenewal(){
  String cid=UUID.randomUUID().toString(),secret=token(),request=UUID.randomUUID().toString(),mac="02:10:20:30:40:52";
  var first=pair(cid,mac,secret,request);
  db.update("UPDATE pairings SET expires_at=0 WHERE id=?",first.get("pairingId"));
  pair(UUID.randomUUID().toString(),"02:10:20:30:40:53",token(),UUID.randomUUID().toString());
  assertEquals("EXPIRED",((Map<?,?>)devices.poll((String)first.get("pairingId"),secret)).get("status"));
  var renewed=pair(cid,mac,secret,request);
  assertNotEquals(first.get("pairingCode"),renewed.get("pairingCode"));
  assertEquals("PENDING",((Map<?,?>)devices.poll((String)first.get("pairingId"),secret)).get("status"));
 }
 @Test void simultaneousSubmissionsHaveExactlyOneOwnerAndConsumeCode() throws Exception {
  var alice=accounts.register("alice"+token().substring(0,8),"甲","strong-test-password");
  var bob=accounts.register("bob"+token().substring(0,8),"乙","strong-test-password");
  String secret=token();
  var pairing=pair(UUID.randomUUID().toString(),"02:10:20:30:40:51",secret,UUID.randomUUID().toString());
  String code=(String)pairing.get("pairingCode");
  var gate=new CountDownLatch(1);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   List<Future<Long>> calls=new ArrayList<>();
   for(long user:new long[]{alice.id(),bob.id()})calls.add(pool.submit(()->{
    gate.await();
    try{devices.bind(code,user);return user;}catch(ResponseStatusException e){assertTrue(Set.of(404,409).contains(e.getStatusCode().value()));return -1L;}
   }));
   gate.countDown();
   long a=calls.get(0).get(10,TimeUnit.SECONDS),b=calls.get(1).get(10,TimeUnit.SECONDS);
   assertTrue((a>0) ^ (b>0));
   assertEquals(Math.max(a,b),devices.authenticate(secret).userId());
  }
  assertThrows(ResponseStatusException.class,()->devices.bind(code,alice.id()));
  assertEquals("BOUND",db.queryForObject("SELECT status FROM pairings WHERE id=?",String.class,pairing.get("pairingId")));
  assertTrue(db.queryForObject("SELECT expires_at FROM pairings WHERE id=?",Long.class,pairing.get("pairingId"))<=System.currentTimeMillis());
 }
 @Test void concurrentAllocationsRemainUniqueAndRetriesAreIdempotent() throws Exception {
  var gate=new CountDownLatch(1);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   List<Future<Map<String,Object>>> calls=new ArrayList<>();
   for(int i=0;i<16;i++){
    final int n=i;
    calls.add(pool.submit(()->{gate.await();return pair("parallel-"+n,String.format("02:20:30:40:50:%02X",n),token(),UUID.randomUUID().toString());}));
   }
   gate.countDown();
   Set<Object> codes=new HashSet<>();
   for(var call:calls)assertTrue(codes.add(call.get(15,TimeUnit.SECONDS).get("pairingCode")));
  }
 }
}
