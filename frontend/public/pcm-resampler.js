// Continuous weighted resampling: carries fractional samples across worklet blocks.
export class PcmResampler {
  constructor(inputRate, outputRate = 16000) {
    this.ratio = inputRate / outputRate
    this.weight = 0
    this.sum = 0
  }
  process(input) {
    const output = []
    for (const sample of input) {
      let remaining = 1
      while (remaining > 1e-8) {
        const part = Math.min(remaining, this.ratio - this.weight)
        this.sum += sample * part
        this.weight += part
        remaining -= part
        if (this.weight >= this.ratio - 1e-8) {
          const value = Math.max(-1, Math.min(1, this.sum / this.ratio))
          output.push(Math.round(value * (value < 0 ? 32768 : 32767)))
          this.weight = 0
          this.sum = 0
        }
      }
    }
    return output
  }
}
