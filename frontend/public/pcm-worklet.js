import { PcmResampler } from './pcm-resampler.js'
class PcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.resampler = new PcmResampler(sampleRate)
    this.frame = new Int16Array(1600)
    this.index = 0
    this.active = true
    this.port.onmessage = ({ data }) => {
      if (data === 'flush') {
        this.active = false
        if (this.index) this.emit(this.index)
        this.port.postMessage({ flushed: true })
      }
    }
  }
  emit(length) {
    const buffer = new ArrayBuffer(length * 2)
    const view = new DataView(buffer)
    for (let i = 0; i < length; i++) view.setInt16(i * 2, this.frame[i], true)
    this.port.postMessage(buffer, [buffer])
    this.index = 0
  }
  process(inputs) {
    if (!this.active) return false
    const input = inputs[0]?.[0]
    if (!input) return true
    for (const value of this.resampler.process(input)) {
      this.frame[this.index++] = value
      if (this.index === this.frame.length) this.emit(this.index)
    }
    return true
  }
}
registerProcessor('pcm-capture', PcmProcessor)
