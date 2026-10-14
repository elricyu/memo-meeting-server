<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Radio, X, Check, Unplug, KeyRound } from 'lucide-vue-next'
import { request } from './api'
type Device = { id: string; clientId: string; mac: string; displayName: string; platform: string; status: string; createdAt: string }
defineProps<{ userName: string }>()
const emit = defineEmits<{ close: []; notify: [message: string] }>()
const devices = ref<Device[]>([]), code = ref(''), busy = ref(false), error = ref(''), confirmUnbind = ref('')
async function load() { devices.value = await request<Device[]>('/devices') }
onMounted(() => { void load().catch(e => error.value = e.message) })
async function bind() {
  if (busy.value || !/^\d{4}$/.test(code.value)) return
  busy.value = true; error.value = ''
  try {
    await request('/devices/bind', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: code.value }) })
    code.value = ''; await load()
    emit('notify', '眼镜已绑定，眼镜将在开始录音后播报提示')
  } catch(e) { error.value = (e as Error).message }
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
<section class="binding-card"><div class="binding-title"><KeyRound :size="18" />验证码绑定眼镜</div>
<p>让眼镜连接 Wi-Fi，按住语音键说“打开会议记录”或“绑定会议账号”，然后输入眼镜播报的四位数字验证码。</p>
<form class="binding-form" @submit.prevent="bind"><label class="code-label" for="glasses-code">四位验证码</label><input id="glasses-code" v-model="code" type="text" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{4}" maxlength="4" placeholder="例如 0382" :disabled="busy" required /><button type="submit" class="primary small" :disabled="busy || !/^\d{4}$/.test(code)"><Check :size="15" />{{ busy ? '绑定中…' : '绑定眼镜' }}</button></form>
<p>验证码五分钟内有效。过期后眼镜会播报新码，请填写最新验证码。绑定成功后眼镜会自动开始会议记录，并在录音启动后提示。</p>
</section><h3 class="devices-heading">已绑定设备 <span>{{ devices.length }}</span></h3><div v-if="!devices.length" class="devices-empty"><Radio :size="25" /><p>还没有绑定眼镜</p><span>绑定后，在眼镜上开始会议即可。</span></div><div v-for="device in devices" :key="device.id" class="device-row"><div class="device-row-head"><span class="meeting-icon live"><Radio :size="21" /></span><div><strong>{{ device.displayName }}</strong><small>{{ device.platform }} · clientId: {{ device.clientId }} · MAC: {{ device.mac }}</small></div><button class="device-unbind" :disabled="busy" @click="confirmUnbind = device.id"><Unplug :size="14" />解绑</button></div><div v-if="confirmUnbind === device.id" class="unbind-confirm"><p>解绑立即停止这副眼镜的录音并使设备凭证失效。历史会议保留在你的账号下。</p><div><button :disabled="busy" @click="confirmUnbind = ''">取消</button><button class="danger-text" :disabled="busy" @click="unbind(device.id)">{{ busy ? '处理中…' : '确认解绑' }}</button></div></div></div>
</section></div>
</template>
<style scoped>
.code-label{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}
</style>
