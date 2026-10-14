<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { request } from './api'
const emit = defineEmits<{ saved: [] }>()
const form = reactive({ url: '', token: '', enabled: false, automatic: true })
const keySet = ref(false), busy = ref(false), message = ref(''), error = ref('')
onMounted(async () => { try { const saved = await request<typeof form & { keySet: boolean }>('/admin/local-speech'); Object.assign(form, saved); keySet.value = saved.keySet } catch(e) { error.value = (e as Error).message } })
async function run(test: boolean) {
  busy.value = true; message.value = ''; error.value = ''
  try {
    const result = await request<{ message?: string; keySet?: boolean }>(`/admin/local-speech${test ? '/test' : ''}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form) }, 35000)
    message.value = result.message || '本地服务配置已保存'; if (!test) { form.token = ''; keySet.value = !!result.keySet; emit('saved') }
  } catch(e) { error.value = (e as Error).message } finally { busy.value = false }
}
</script>
<template>
  <section class="local-speech-settings" style="border-top:1px solid var(--border);margin-top:24px;padding-top:20px">
    <h3>本地转录 · 自动兜底</h3><p class="ai-intro">独立部署的本地处理服务。自动排队处理已结束且未转录的录音，保留已有原文；不可用时等待恢复，不自动调用云端。</p>
    <form @submit.prevent="run(false)"><fieldset class="ai-fields" :disabled="busy">
      <label>服务地址<input v-model="form.url" required maxlength="2048" placeholder="http://127.0.0.1:18192" /></label>
      <label>访问 Token <span class="ai-optional">可选</span><input v-model="form.token" type="password" autocomplete="new-password" :placeholder="keySet ? '已保存，留空保留' : '仅 SSH 隧道访问时可留空'" maxlength="4096" /></label>
      <label class="ai-checkbox"><input v-model="form.enabled" type="checkbox" />启用本地服务</label>
      <label class="ai-checkbox"><input v-model="form.automatic" type="checkbox" />自动排队转录未转录的会议（含历史录音）</label>
    </fieldset><p v-if="error" class="alert">{{ error }}</p><p v-if="message" class="ai-save-notice" role="status">{{ message }}</p>
    <div class="ai-footer"><p>测试仅检查服务健康状态，不提交录音。已有云端转录和人工修订不会被覆盖。</p><div><button type="button" class="secondary" :disabled="busy" @click="run(true)">测试本地连接</button><button class="primary" :disabled="busy" type="submit">保存本地配置</button></div></div></form>
  </section>
</template>
