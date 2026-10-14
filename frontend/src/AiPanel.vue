<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { X, AudioLines, FileText, Check, PlugZap, Save, Users, KeyRound } from 'lucide-vue-next'
import { request } from './api'
type Kind = 'asr' | 'llm'
type View = { type: Kind; mode: string; provider: string; url: string; model: string; vocabularyId: string; language: string; diarization: boolean; supportsDiarization: boolean; reuseAsrKey: boolean; keySet: boolean; ready: boolean; updatedAt: string | null }
type Form = { mode: string; provider: string; model: string; vocabularyId: string; language: string; diarization: boolean; reuseAsrKey: boolean; apiKey: string }
type Test = { ok: boolean; message: string; latencyMs: number }
const emit = defineEmits<{ close: []; saved: []; notify: [message: string] }>()
const views = ref<Record<Kind, View>>(), forms = ref<Record<Kind, Form>>(), error = ref('')
const pending = ref<Record<Kind, string>>({ asr: '', llm: '' }), results = ref<Partial<Record<Kind, Test>>>({}), errors = ref({ asr: '', llm: '' })
const busy = computed(() => !!pending.value.asr || !!pending.value.llm)
const kinds: Kind[] = ['asr', 'llm']
const titles = { asr: '实时语音转录', llm: 'AI 会议纪要' }
const providers = { asr: [{ id: 'DASHSCOPE_CN', name: '阿里云百炼 · 北京' }, { id: 'DASHSCOPE_INTL', name: '阿里云百炼 · 新加坡' }, { id: 'DEEPGRAM', name: 'Deepgram · 支持实时说话人分离' }], llm: [{ id: 'DASHSCOPE_CN', name: '阿里云百炼 · 北京' }, { id: 'DASHSCOPE_INTL', name: '阿里云百炼 · 新加坡' }, { id: 'DEEPSEEK', name: 'DeepSeek' }] }
const endpoint = (kind: Kind, provider: string) => provider === 'DEEPGRAM' ? 'wss://api.deepgram.com/v1/listen' : provider === 'DEEPSEEK' ? 'https://api.deepseek.com/chat/completions' : `${kind === 'asr' ? 'wss' : 'https'}://dashscope${provider === 'DASHSCOPE_INTL' ? '-intl' : ''}.aliyuncs.com/${kind === 'asr' ? 'api-ws/v1/inference' : 'compatible-mode/v1/chat/completions'}`
function defaults(kind: Kind, provider = 'DASHSCOPE_CN'): Form { return { mode: 'ACCOUNT', provider, model: kind === 'asr' ? provider === 'DEEPGRAM' ? 'nova-3' : 'fun-asr-realtime' : provider === 'DEEPSEEK' ? 'deepseek-chat' : 'qwen-plus', vocabularyId: '', language: 'zh', diarization: provider === 'DEEPGRAM', reuseAsrKey: false, apiKey: '' } }
async function load() {
  views.value = await request<Record<Kind, View>>('/ai/settings')
  const formFor = (kind: Kind): Form => { const view = views.value![kind]; return { mode: view.ready ? view.mode : 'ACCOUNT', provider: view.provider === 'SERVER' ? 'DASHSCOPE_CN' : view.provider, model: view.model, vocabularyId: view.vocabularyId, language: view.language, diarization: view.diarization, reuseAsrKey: view.reuseAsrKey, apiKey: '' } }
  forms.value = { asr: formFor('asr'), llm: formFor('llm') }
}
onMounted(() => void load().catch(e => error.value = e.message))
watch(forms, () => { results.value = {} }, { deep: true })
function providerChanged(kind: Kind) { if (forms.value) forms.value[kind] = defaults(kind, forms.value[kind].provider); errors.value[kind] = '' }
async function perform(kind: Kind, operation: 'save' | 'test') {
  if (!forms.value || busy.value) return
  pending.value[kind] = operation; errors.value[kind] = ''; results.value[kind] = undefined
  try {
    const body = JSON.stringify(forms.value[kind])
    if (operation === 'save') {
      await request(`/ai/settings/${kind}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
      // Do not retain plaintext keys after saving or place them in browser storage.
      forms.value[kind].apiKey = ''
      views.value = await request<Record<Kind, View>>('/ai/settings')
      emit('saved'); emit('notify', `${titles[kind]}配置已保存，下次任务开始时生效`)
    } else results.value[kind] = await request<Test>(`/ai/settings/${kind}/test`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body }, 60000)
  } catch (e) { errors.value[kind] = (e as Error).message }
  finally { pending.value[kind] = '' }
}
function close() { if (!busy.value) emit('close') }
</script>

<template>
  <div class="modal-backdrop" @click.self="close">
    <section class="ai-modal" role="dialog" aria-modal="true" aria-labelledby="ai-title" @keydown.esc="close">
      <header class="ai-header"><div><span class="eyebrow">AI FOR YOUR MEETINGS</span><h2 id="ai-title">让 AI 听清，也记准。</h2><p>当前账号的网页与已绑定眼镜，共用这一套 AI 配置。</p></div><button class="icon-button" aria-label="关闭 AI 设置" :disabled="busy" @click="close"><X :size="20" /></button></header>
      <div class="ai-overview"><span><AudioLines :size="17" />音频 → 实时文字</span><span><Users :size="17" />声音 → 说话人</span><span><FileText :size="17" />原文 → 纪要与待办</span></div>
      <div v-if="error" class="alert" role="alert">{{ error }}</div>
      <p v-if="!forms && !error" class="ai-loading">正在读取配置…</p>
      <div v-if="forms && views" class="ai-cards">
        <form v-for="kind in kinds" :key="kind" class="ai-card" :aria-label="titles[kind] + '配置'" @submit.prevent="perform(kind, 'save')">
          <div class="ai-card-heading"><span class="ai-card-icon"><AudioLines v-if="kind === 'asr'" :size="22" /><FileText v-else :size="22" /></span><div><h3>{{ titles[kind] }}</h3><p>{{ kind === 'asr' ? '边说边看，保留音频与原文。' : '提炼摘要、结论、待办与未决问题。' }}</p></div><span class="ai-status" :class="{ ready: views[kind].ready }">{{ views[kind].ready ? '已配置' : '待配置' }}</span></div>
          <fieldset :disabled="busy" class="ai-fields">
            <label class="ai-field">配置来源<select v-model="forms[kind].mode"><option value="ACCOUNT">我的账号配置</option><option value="SERVER">使用服务默认配置</option></select></label>
            <template v-if="forms[kind].mode === 'ACCOUNT'">
              <label class="ai-field">AI 服务<select v-model="forms[kind].provider" @change="providerChanged(kind)"><option v-for="provider in providers[kind]" :key="provider.id" :value="provider.id">{{ provider.name }}</option></select></label>
              <label class="ai-field">模型 ID<input v-model="forms[kind].model" required maxlength="128" :list="`models-${kind}`" :aria-label="titles[kind] + '模型 ID'" spellcheck="false" /><datalist :id="`models-${kind}`"><template v-if="kind === 'asr'"><option v-if="forms[kind].provider === 'DEEPGRAM'" value="nova-3" /><option v-if="forms[kind].provider === 'DEEPGRAM'" value="nova-2-general" /><option v-if="forms[kind].provider !== 'DEEPGRAM'" value="fun-asr-realtime" /><option v-if="forms[kind].provider !== 'DEEPGRAM'" value="paraformer-realtime-v2" /></template><template v-else><option v-if="forms[kind].provider.startsWith('DASHSCOPE')" value="qwen-plus" /><option v-if="forms[kind].provider.startsWith('DASHSCOPE')" value="qwen-max" /><option v-if="forms[kind].provider === 'DEEPSEEK'" value="deepseek-chat" /></template></datalist></label>
              <label v-if="kind === 'llm' && forms[kind].provider.startsWith('DASHSCOPE')" class="ai-checkbox"><input v-model="forms[kind].reuseAsrKey" type="checkbox" />使用同地域的已保存转录 Key</label>
              <label v-if="!forms[kind].reuseAsrKey" class="ai-field">API Key <span v-if="views[kind].mode === 'ACCOUNT' && views[kind].provider === forms[kind].provider && views[kind].keySet" class="ai-key-hint"><KeyRound :size="12" />已保存，留空保留</span><input v-model="forms[kind].apiKey" type="password" maxlength="2048" autocomplete="new-password" :aria-label="titles[kind] + ' API Key'" :placeholder="views[kind].mode === 'ACCOUNT' && views[kind].provider === forms[kind].provider && views[kind].keySet ? '填写新 Key 可替换原 Key' : '粘贴该服务的 API Key'" spellcheck="false" /></label>
              <template v-if="kind === 'asr' && forms[kind].provider === 'DEEPGRAM'"><label class="ai-field">识别语言<select v-model="forms[kind].language"><option value="zh">中文（普通话）</option><option value="en">英语</option><option value="multi">多语言（不含中文）</option><option value="ja">日语</option><option value="ko">韩语</option></select></label><label class="ai-checkbox"><input v-model="forms[kind].diarization" type="checkbox" />按声音区分说话人</label><p class="ai-help">显示“说话人 1 / 2”，录音结束后可改名。编号只用于本次录音，不会自动认出真实姓名；续录会重新编号。</p></template>
              <template v-if="kind === 'asr' && forms[kind].provider !== 'DEEPGRAM'"><label class="ai-field">热词表 ID <span class="ai-optional">选填</span><input v-model="forms[kind].vocabularyId" maxlength="128" placeholder="该地域创建的百炼热词表 ID" /></label><p class="ai-help">当前百炼实时模型不支持说话人分离。需要分人时选择 Deepgram，并用真实中文录音比较效果。</p></template>
              <details class="ai-endpoint"><summary>服务地址</summary><code>{{ endpoint(kind, forms[kind].provider) }}</code><p>Key、模型与服务地域需要一致。</p></details>
            </template>
            <div v-else class="ai-server"><p>{{ views[kind].mode === 'SERVER' && views[kind].ready ? '服务默认配置已就绪。' : '保存后使用服务端环境变量提供的配置；未提供 Key 时该功能不可用。' }}</p><p>现有账号 Key 将在保存后移除。</p></div>
          </fieldset>
          <div v-if="errors[kind]" class="ai-test-result failed" role="alert">{{ errors[kind] }}</div>
          <div v-if="results[kind]" class="ai-test-result" :class="{ failed: !results[kind]!.ok }" role="status"><strong><Check v-if="results[kind]!.ok" :size="15" />{{ results[kind]!.ok ? '连接测试通过' : '连接测试失败' }} · {{ (results[kind]!.latencyMs / 1000).toFixed(1) }} 秒</strong><p>{{ results[kind]!.message }}</p></div>
          <div class="ai-actions"><button type="button" class="secondary small" :disabled="busy" @click="perform(kind, 'test')"><PlugZap :size="15" />{{ pending[kind] === 'test' ? '测试中…' : '测试连接' }}</button><button class="primary small" :disabled="busy"><Save :size="15" />{{ pending[kind] === 'save' ? '保存中…' : '保存配置' }}</button></div>
        </form>
      </div>
      <footer class="ai-footer"><p><KeyRound :size="14" />Key 加密保存在服务端，不会发给眼镜或返回页面。新配置从下一次录音、重转录或纪要任务开始生效。</p><p>连接测试使用少量静音或测试文字，可能产生少量云端费用，不会上传已有会议。通过测试只说明接口可用，不代表真实识别准确率。</p><p>声纹身份识别（预先登记声音、自动认出姓名）尚未接入。当前分人功能只区分本次录音里的不同声音。</p></footer>
    </section>
  </div>
</template>
