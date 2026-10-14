// Test-only end-to-end QR generation/decoding and session/device protocol.
import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import fs from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import qrcode from '../frontend/node_modules/qrcode-generator/dist/qrcode.mjs'
import { Session, native } from './session.mjs'
const base=process.env.TEST_URL,folder=process.env.QR_TEST_DIR,java=process.env.JAVA_HOME?`${process.env.JAVA_HOME}/bin/java`:'java'
const owner=new Session(base),other=new Session(base);await owner.register('qr_owner');await other.register('qr_other')
const first=await owner.api('/devices/web-bindings','POST');assert.equal(first.status,200)
const qr=first.body;assert.equal(qr.qrToken.length,64);assert.equal((await other.api(`/devices/web-bindings/${qr.id}`)).status,404)
const clientToken=randomBytes(32).toString('hex');const device={clientId:`QR-fixture-${randomUUID()}`,mac:`02:AA:BB:${randomBytes(3).toString('hex').match(/../g).join(':')}`,displayName:'隔离扫码测试眼镜',platform:'RV1106B',deviceToken:clientToken,clientRequestId:randomUUID()}
const encoded=qrcode(0,'M');encoded.addData(JSON.stringify({v:1,requestId:qr.id,token:qr.qrToken}));encoded.make()
await fs.writeFile(`${folder}/qr.gif`,Buffer.from(encoded.createDataURL(8,32).split(',')[1],'base64'))
const converted=spawnSync(java,['scripts/QrFixture.java',`${folder}/qr.gif`,`${folder}/qr.jpg`],{encoding:'utf8'});assert.equal(converted.status,0,converted.stderr)
const photo=(await fs.readFile(`${folder}/qr.jpg`)).toString('base64')
assert.equal((await native(base,'/devices/binding-claims','POST',{device,requestId:qr.id,token:'0'.repeat(64)})).status,404)
const claimed=await native(base,'/devices/binding-claims/scan','POST',{device,jpegBase64:photo});assert.equal(claimed.status,200,JSON.stringify(claimed.body))
const pairing=claimed.body;assert.equal((await owner.api(`/devices/web-bindings/${qr.id}`)).body.status,'CLAIMED')
assert.equal((await native(base,`/devices/pairings/${pairing.pairingId}`,'GET',null,clientToken)).body.status,'PENDING')
assert.equal((await native(base,'/meetings','POST',{title:'尚未确认'},clientToken)).status,401)
assert.equal((await other.api(`/devices/web-bindings/${qr.id}/confirm`,'POST')).status,404)
assert.equal((await owner.api(`/devices/web-bindings/${qr.id}/confirm`,'POST')).status,200)
assert.equal((await native(base,`/devices/pairings/${pairing.pairingId}`,'GET',null,clientToken)).body.status,'BOUND')
const recovered=await native(base,'/devices/binding-claims/recover','POST',{clientRequestId:device.clientRequestId,deviceToken:clientToken});assert.equal(recovered.body.pairingId,pairing.pairingId)
const meeting=await native(base,'/meetings','POST',{title:'扫码绑定之后'},clientToken);assert.equal(meeting.status,200);assert.equal(meeting.body.meeting.ownerId,owner.user.id);assert.equal(meeting.body.meeting.source,'GLASSES')
assert.equal((await native(base,'/devices/binding-claims/scan','POST',{device,jpegBase64:photo})).status,404)
await owner.api(`/devices/${pairing.deviceId}/unbind`,'POST');assert.equal((await native(base,'/meetings','GET',null,clientToken)).status,401)
console.log('PASS: Vue QR -> JPEG -> Spring Boot ZXing; CSRF/session owner scope; claim stays unauthenticated until confirmation; credentials recovery; meeting owner; QR replay rejection; unbind revocation')
