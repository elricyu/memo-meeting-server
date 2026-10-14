/** Public wire codec shared by browser and protocol tests. MM01 is little-endian. */
export function encodeAudioFrame(pcm, sequence, offsetBytes) {
  if (!(pcm instanceof ArrayBuffer) || pcm.byteLength < 2 || pcm.byteLength > 32000 || pcm.byteLength % 2) throw new Error('Invalid PCM frame length')
  if (!Number.isSafeInteger(sequence) || sequence < 0 || sequence > 0xffffffff || !Number.isSafeInteger(offsetBytes) || offsetBytes < 0 || offsetBytes % 2) throw new Error('Invalid sequence or byte offset')
  const frame = new ArrayBuffer(16 + pcm.byteLength)
  new Uint8Array(frame, 0, 4).set([77, 77, 48, 49])
  const view = new DataView(frame)
  view.setUint32(4, sequence, true)
  view.setBigUint64(8, BigInt(offsetBytes), true)
  new Uint8Array(frame, 16).set(new Uint8Array(pcm))
  return frame
}
