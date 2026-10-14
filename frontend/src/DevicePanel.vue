<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { Radio, X, Check, Unplug, ScanLine, RefreshCw } from 'lucide-vue-next'
import qrcode from 'qrcode-generator'
import { request } from './api'
type Device = { id: string; clientId: string; mac: string; displayName: string; platform: string; status: string; createdAt: string }
type Pending = { id: string; qrToken: string | null; expiresAt: string; status: string; device: Device | null }
defineProps<{ userName: string }>()
const emit = defineEmits<{ close: []; notify: [message: string] }>()
const devices = ref<Device[]>([]), pending = ref<Pending>(), qrImage = ref(''), busy = ref(false), error = ref(''), confirmUnbind = ref(''), now = ref(Date.now())
const seconds = computed(() => Math.max(0, Math.ceil((new Date(pending.value?.expiresAt || 0).getTime() - now.value) / 1000)))
const expired = computed(() => !!pending.value && (seconds.value === 0 || ['EXPIRED', 'CANCELLED'].includes(pending.value.status)))
let polling = false, closed = false, timer: ReturnType<typeof setInterval> | undefined
async function load() { devices.value = await request<Device[]>('/devices') }
onMounted(() => { void load().catch(e => error.value = e.message); timer = setInterval(() => { now.value = Date.now(); void poll() }, 2000) })
onUnmounted(() => { closed = true; clearInterval(timer) })
async function start() {
  busy.value = true; error.value = ''
  try {
    const value = await request<Pending>('/devices/web-bindings', { method: 'POST' })
    if (closed) return
    pending.value = value; now.value = Date.now()
    const qr = qrcode(0, 'M'); qr.addData(JSON.stringify({ v: 1, requestId: value.id, token: value.qrToken })); qr.make()
    qrImage.value = qr.createDataURL(6, 24)
  } catch(e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function poll() {
  if (polling || busy.value || !pending.value || !['WAITING', 'CLAIMED'].includes(pending.value.status) || expired.value) return
  polling = true
  try { const value = await request<Pending>(`/devices/web-bindings/${pending.value.id}`); if (!closed) { pending.value = value; if (value.status === 'BOUND') { qrImage.value = ''; await load() } } }
  catch(e) { if (!closed) error.value = (e as Error).message }
  finally { polling = false }
}
async function bind() {
  if (!pending.value) return
  busy.value = true; error.value = ''
  try { await request(`/devices/web-bindings/${pending.value.id}/confirm`, { method: 'POST' }); pending.value = undefined; qrImage.value = ''; await load(); emit('notify', '眼镜已绑定，此后的会议会自动归入你的账号') }
  catch(e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function cancel() {
  if (!pending.value) return
  busy.value = true
  try { await request(`/devices/web-bindings/${pending.value.id}/cancel`, { method: 'POST' }); pending.value = undefined; qrImage.value = '' }
  catch(e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function unbind(id: string) {
  busy.value = true; error.value = ''
  try { await request(`/devices/${id}/unbind`, { method: 'POST' }); confirmUnbind.value = ''; await load(); emit('notify', '设备已解绑，历史会议仍保留在你的账号下') }
  catch(e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
</script>
<template>
<div class="modal-backdrop" @click.self="!busy && emit('close')"><section class="device-modal" role="dialog" aria-modal="true" aria-labelledby="device-title" @keydown.esc="!busy && emit('close')">
<button class="modal-close icon-button" aria-label="关闭设备管理" :disabled="busy" @click="emit('close')"><X :size="19" /></button><span class="eyebrow">CONNECTED TO YOU</span><h2 id="device-title">你的眼镜，你的会议。</h2><p class="device-intro">绑定到 {{ userName }} 的眼镜，创建的会议自动归入你的账号。</p><div v-if="error" class="alert" role="alert">{{ error }}</div>
<section class="binding-card"><div class="binding-title"><ScanLine :size="18" />扫码绑定眼镜</div>
<template v-if="!pending"><p>让眼镜连接 Wi-Fi，与此网页使用同一个会议服务。</p><ol class="qr-steps"><li>点击下方按钮，生成绑定二维码。</li><li>按住眼镜语音键说“打开会议记录”或“绑定会议账号”，松开后看向二维码。</li><li>核对眼镜的 clientId 与 MAC，确认绑定。</li></ol><button class="primary small" :disabled="busy" @click="start"><ScanLine :size="16" />{{ busy ? '正在生成…' : '生成绑定二维码' }}</button><p class="qr-note">无需 USB、终端或语音报码。扫码客户端需安装在眼镜上。</p></template>
<div v-else-if="expired" class="qr-expired"><p>二维码已过期，请重新生成。</p><button class="primary small" :disabled="busy" @click="start"><RefreshCw :size="15" />重新生成</button></div>
<div v-else-if="pending.status === 'WAITING'" class="qr-waiting"><img :src="qrImage" alt="会议账号绑定二维码" class="binding-qr" /><strong>让眼镜看向二维码</strong><p>按住眼镜语音键说“打开会议记录”或“绑定会议账号”，听到扫码提示后正对二维码。仅看向二维码不会启动扫码。</p><span role="status">等待眼镜识别二维码 · {{ Math.floor(seconds / 60) }}:{{ String(seconds % 60).padStart(2, '0') }} 后过期</span><button class="secondary small" :disabled="busy" @click="cancel">取消绑定</button><p class="qr-note">二维码仅在本次绑定中有效。关闭此窗口后可重新生成。</p></div>
<div v-else-if="pending.status === 'CLAIMED' && pending.device" class="binding-preview"><div><Radio :size="24" /><strong>{{ pending.device.displayName }}</strong><small>{{ pending.device.platform }}</small><small>clientId：{{ pending.device.clientId }}</small><small>MAC：{{ pending.device.mac }}</small></div><p>这副眼镜已扫码申请绑定到 {{ userName }}。请核对设备，确认后才能创建会议。</p><div class="binding-actions"><button class="secondary small" :disabled="busy" @click="cancel">取消申请</button><button class="primary small" :disabled="busy" @click="bind"><Check :size="15" />{{ busy ? '绑定中…' : '确认绑定' }}</button></div></div>
<div v-else-if="pending.status === 'BOUND'" class="qr-expired"><p>绑定已完成。</p><button class="secondary small" @click="pending = undefined">完成</button></div>
</section><h3 class="devices-heading">已绑定设备 <span>{{ devices.length }}</span></h3><div v-if="!devices.length" class="devices-empty"><Radio :size="25" /><p>还没有绑定眼镜</p><span>绑定后，在眼镜上开始会议即可。</span></div><div v-for="device in devices" :key="device.id" class="device-row"><div class="device-row-head"><span class="meeting-icon live"><Radio :size="21" /></span><div><strong>{{ device.displayName }}</strong><small>{{ device.platform }} · clientId: {{ device.clientId }} · MAC: {{ device.mac }}</small></div><button class="device-unbind" :disabled="busy" @click="confirmUnbind = device.id"><Unplug :size="14" />解绑</button></div><div v-if="confirmUnbind === device.id" class="unbind-confirm"><p>解绑立即停止这副眼镜的录音并使设备凭证失效。历史会议保留在你的账号下。</p><div><button :disabled="busy" @click="confirmUnbind = ''">取消</button><button class="danger-text" :disabled="busy" @click="unbind(device.id)">{{ busy ? '处理中…' : '确认解绑' }}</button></div></div></div>
</section></div>
</template>
<style scoped>
.qr-steps{padding-left:20px;font-size:13px;color:#52617b;display:grid;gap:7px;margin:14px 0 18px}.qr-note{font-size:12px!important;color:#64748b!important;margin-top:12px!important}.qr-waiting{display:flex;flex-direction:column;align-items:center;gap:10px;text-align:center;padding-top:16px}.binding-qr{width:min(290px,100%);height:auto;background:#fff;image-rendering:pixelated;box-shadow:0 0 0 1px #e4e9f2;border-radius:4px}.qr-waiting>strong{font-size:16px}.qr-waiting>span{font-size:12px;color:#3458db}.qr-expired{padding:20px 0;text-align:center;display:grid;gap:15px;justify-items:center}.binding-preview small{display:block;overflow-wrap:anywhere}
</style>
