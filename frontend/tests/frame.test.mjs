import { test } from 'node:test'
import assert from 'node:assert/strict'
import { encodeAudioFrame } from '../public/audio-frame.js'
test('MM01 round trips little-endian unsigned sequence and 64-bit offsets', () => {
  const input = new Uint8Array([255, 127, 0, 128])
  const frame = encodeAudioFrame(input.buffer, 0xffffffff, 2 ** 34)
  const header = new DataView(frame)
  assert.equal(new TextDecoder().decode(new Uint8Array(frame, 0, 4)), 'MM01')
  assert.equal(header.getUint32(4, true), 0xffffffff)
  assert.equal(header.getBigUint64(8, true), 2n ** 34n)
  assert.deepEqual(new Uint8Array(frame, 16), input)
})
test('invalid audio payload and unaligned offsets are rejected', () => {
  for (const size of [0, 1, 32002]) assert.throws(() => encodeAudioFrame(new ArrayBuffer(size), 0, 0))
  assert.throws(() => encodeAudioFrame(new ArrayBuffer(2), -1, 0))
  assert.throws(() => encodeAudioFrame(new ArrayBuffer(2), 0, 1))
})
