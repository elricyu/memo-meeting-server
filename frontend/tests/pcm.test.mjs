import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PcmResampler } from '../public/pcm-resampler.js'
for (const rate of [16000, 44100, 48000]) {
  test(`continuous ${rate} Hz audio produces exactly 16000 samples per second`, () => {
    const r = new PcmResampler(rate)
    const source = new Float32Array(rate).fill(.5)
    let result = []
    for (let i = 0; i < source.length; i += 128) result.push(...r.process(source.subarray(i, i + 128)))
    assert.equal(result.length, 16000)
    assert.ok(result.every(x => Math.abs(x - 16384) <= 1))
  })
}
test('PCM clipping is bounded and signed', () => {
  assert.deepEqual(new PcmResampler(16000).process(new Float32Array([-2, -1, 0, 1, 2])), [-32768, -32768, 0, 32767, 32767])
})
