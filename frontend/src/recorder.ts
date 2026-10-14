import { encodeAudioFrame } from '../public/audio-frame.js'
export class Recorder {
  private socket?: WebSocket
  private stream?: MediaStream
  private context?: AudioContext
  private node?: AudioWorkletNode
  private stopTimer?: ReturnType<typeof setTimeout>
  private heartbeat?: ReturnType<typeof setInterval>
  private stopped = false
  private stopping = false
  private active = false
  private onEnd: () => void
  constructor(private onError: (message: string) => void, onEnd: () => void, private onLevel: (level: number) => void) { this.onEnd = onEnd }
  async start(id: string, token: string) {
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) throw new Error('麦克风需要 HTTPS 或 localhost。其他设备请通过 HTTPS 访问。')
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } })
      this.context = new AudioContext()
      await this.context.resume()
      await this.context.audioWorklet.addModule('/pcm-worklet.js')
      this.node = new AudioWorkletNode(this.context, 'pcm-capture')
      const source = this.context.createMediaStreamSource(this.stream)
      const mute = this.context.createGain(); mute.gain.value = 0
      // Capture starts only after ready, preventing stale startup audio from queuing.
      this.node.connect(mute); mute.connect(this.context.destination)
      const socket = this.socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/v1/meetings/${id}/audio`)
      let ready = false, sequence = 0, offsetBytes = 0
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('云端识别连接超时')), 45000)
        socket.onopen = () => socket.send(JSON.stringify({ type: 'start', token, protocolVersion: 1, audio: { format: 'pcm_s16le', sampleRate: 16000, channels: 1, bitsPerSample: 16 } }))
        socket.onmessage = ({ data }) => {
          const message = JSON.parse(data)
          if (message.type === 'ready') { clearTimeout(timer); offsetBytes = message.offsetBytes; ready = true; this.active = true; resolve() }
          if (message.type === 'error') {
            clearTimeout(timer)
            if (!ready) reject(new Error(message.message)); else { this.onError(message.message); void this.cleanup() }
          }
          if (message.type === 'finished') void this.cleanup()
        }
        socket.onerror = () => { clearTimeout(timer); if (!ready) reject(new Error('记录端连接失败')); else this.onError('录音连接异常') }
        socket.onclose = () => {
          clearTimeout(timer)
          if (!ready) reject(new Error('连接已关闭'))
          else if (!this.stopped && !this.stopping) this.onError('录音连接已断开。已接收音频已保存，请点击继续录音。')
          void this.cleanup()
        }
      })
      if (this.stopped) throw new Error('识别连接已中断')
      this.node.port.onmessage = ({ data }) => {
        if (!(data instanceof ArrayBuffer) || !this.active) return
        if (socket.readyState !== WebSocket.OPEN || socket.bufferedAmount > 32000 * 5) {
          this.onError('网络无法及时发送音频，录音已中断。已接收音频可回听和重新转录。'); void this.cleanup(); return
        }
        const samples = new Int16Array(data)
        let sum = 0; for (const x of samples) sum += x * x
        this.onLevel(Math.min(1, Math.sqrt(sum / samples.length) / 10000))
        socket.send(encodeAudioFrame(data, sequence++, offsetBytes))
        offsetBytes += data.byteLength
      }
      source.connect(this.node)
      this.heartbeat = setInterval(() => { if (socket.readyState === WebSocket.OPEN && !this.stopping) socket.send(JSON.stringify({ type: 'ping' })) }, 15000)
      this.stream.getAudioTracks()[0]!.onended = () => { if (!this.stopping) { this.onError('麦克风已停止，请重新开始录音'); void this.cleanup() } }
    } catch (error) {
      await this.cleanup()
      if (error instanceof DOMException) {
        if (error.name === 'NotAllowedError') throw new Error('请允许浏览器使用麦克风，然后重新开始录音')
        if (error.name === 'NotFoundError') throw new Error('没有找到可用的麦克风')
        if (error.name === 'NotReadableError') throw new Error('麦克风无法读取，请检查是否被其他应用占用')
      }
      throw error
    }
  }
  async stop() {
    if (this.stopping || this.stopped) return
    this.stopping = true
    if (this.node) {
      await new Promise<void>((resolve) => {
        const old = this.node!.port.onmessage
        const timer = setTimeout(resolve, 1500)
        this.node!.port.onmessage = (event) => {
          if (event.data?.flushed) { clearTimeout(timer); resolve() }
          else old?.call(this.node!.port, event)
        }
        this.node!.port.postMessage('flush')
      })
    }
    this.active = false
    this.stream?.getTracks().forEach(track => track.stop())
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ type: 'stop' }))
    else await this.cleanup()
    // Keep the socket open until the cloud confirms the final sentence.
    this.stopTimer = setTimeout(() => { if (!this.stopped) { this.onError('等待最终转录超时，请在记录中检查状态'); void this.cleanup() } }, 35000)
  }
  async cleanup() {
    if (this.stopped) return
    this.stopped = true; this.active = false
    clearInterval(this.heartbeat); clearTimeout(this.stopTimer)
    this.stream?.getTracks().forEach(track => track.stop())
    this.node?.disconnect()
    if (this.context && this.context.state !== 'closed') await this.context.close()
    this.socket?.close()
    this.onLevel(0); this.onEnd()
  }
}
