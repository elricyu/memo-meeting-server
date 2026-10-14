import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PcmResampler } from '../public/pcm-resampler.js'
for (const rate of [8000, 16000, 44100, 48000]) {
  test(`${rate}Hz → 16kHz keeps duration and level across blocks`, () => {
    const resampler = new PcmResampler(rate)
    const samples = new Float32Array(rate).fill(0.5)
    const output = []
    for (let i = 0; i < rate; i += 128) output.push(...resampler.process(samples.subarray(i, i + 128)))
    assert.equal(output.length, 16000)
    assert.ok(output.every(sample => Math.abs(sample - 16384) <= 1))
  })
}
test('clips audio and preserves signed PCM range', () => {
  assert.deepEqual(new PcmResampler(16000).process([-2, -1, 0, 1, 2]), [-32768, -32768, 0, 32767, 32767])
})
test('output does not depend on frame boundary', () => {
  const input = Array.from({ length: 48000 }, (_, i) => Math.sin(i / 48))
  const whole = new PcmResampler(48000).process(input)
  const block = new PcmResampler(48000), output = []
  for (let i = 0; i < input.length; i += 107) output.push(...block.process(input.slice(i, i + 107)))
  assert.deepEqual(output, whole)
})
