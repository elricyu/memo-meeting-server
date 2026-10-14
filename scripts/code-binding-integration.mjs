import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { Session, native } from './session.mjs'
const base = process.env.TEST_URL
const alice = new Session(base), bob = new Session(base)
await alice.register('code_a'); await bob.register('code_b')
const token = randomBytes(32).toString('hex')
const device = { clientId: randomUUID(), mac: randomBytes(6).toString('hex').match(/../g).join(':'), displayName: '验证码测试眼镜', platform: 'RV1106B', deviceToken: token, clientRequestId: randomUUID() }
const attempts = await Promise.all([0, 1, 2].map(() => native(base, '/devices/pairings', 'POST', device)))
for (const result of attempts) { assert.equal(result.status, 200); assert.deepEqual(result.body, attempts[0].body) }
const pairing = attempts[0].body
assert.match(pairing.pairingCode, /^[0-9]{4}$/)
const competing = await Promise.all([alice, bob].map(session => session.api('/devices/bind', 'POST', { code: pairing.pairingCode })))
assert.equal(competing.filter(result => result.status === 200).length, 1)
assert.ok(competing.some(result => [404, 409].includes(result.status)))
const winner = competing[0].status === 200 ? alice : bob
const poll = await native(base, `/devices/pairings/${pairing.pairingId}`, 'GET', null, token)
assert.equal(poll.body.status, 'BOUND'); assert.equal(poll.body.owner.id, winner.user.id)
assert.equal((await winner.api('/devices/bind', 'POST', { code: pairing.pairingCode })).status, 404)
const created = await native(base, '/meetings', 'POST', { title: '验证码绑定后创建会议' }, token)
assert.equal(created.status, 200); assert.equal(created.body.meeting.ownerId, winner.user.id)
assert.equal((await winner.api(`/devices/${pairing.deviceId}/unbind`, 'POST')).status, 200)
assert.equal((await native(base, '/meetings', 'GET', null, token)).status, 401)
console.log('PASS: four-digit code, concurrent idempotent requests, first successful owner, immediate consumption, meeting ownership and revocation')
