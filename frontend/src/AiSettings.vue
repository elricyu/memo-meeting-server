<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { X, Check, Radio, FileText, ShieldCheck, LoaderCircle, ArrowUpRight } from 'lucide-vue-next'
import { request } from './api'
import { confirmAiSave } from './ai-save.js'
type Kind = 'asr' | 'llm'
type Input = { mode: string; provider: string; model: string; vocabularyId: string; language: string; diarization: boolean; reuseAsrKey: boolean; apiKey: string; workspaceId: string; appId: string; secretId: string; secretKey: string }
type View = Omit<Input, 'apiKey' | 'secretId' | 'secretKey'> & { ready: boolean; keySet: boolean; url: string; updatedAt: string | null }
type Model = { provider: string; model: string; label: string; partialResults: boolean; twoPass: boolean; speakerMode: 'NONE' | 'REALTIME' | 'STREAMING_SENTENCE'; speakerRequired: boolean; languages: string[]; note: string; documentation: string }
type Result = { ok: boolean; code: string; message: string; elapsedMs: number }
const emit = defineEmits<{ close: []; saved: [] }>()
const kind = ref<Kind>('asr'), loading = ref(true), saving = ref(false), testing = ref(false), error = ref(''), notice = ref(''), result = ref<Result>()
const views = ref<Record<Kind, View>>()
const catalog = ref<Model[]>([])
const models = computed(() => catalog.value.filter(m => m.provider === form.provider))
const capability = computed(() => models.value.find(m => m.model === form.model))
const canSeparate = computed(() => !!capability.value && capability.value.speakerMode !== 'NONE' && capability.value.languages.includes(form.language))
const form = reactive<Input>({ mode: 'GLOBAL', provider: 'DASHSCOPE_CN', model: 'fun-asr-realtime', vocabularyId: '', language: 'zh', diarization: false, reuseAsrKey: false, apiKey: '', workspaceId: '', appId: '', secretId: '', secretKey: '' })
const testedDraft = ref(false)
const busy = computed(() => saving.value || testing.value)
const providers = computed(() => kind.value === 'asr' ? [{ id: 'DASHSCOPE_CN', name: '阿里云百炼 · 北京' }, { id: 'DOUBAO', name: '豆包语音 · 新接入待实测' }] : [{ id: 'DASHSCOPE_CN', name: '阿里云百炼 · 北京' }])
function fill() {
  const view = views.value?.[kind.value]
  const supported = view && providers.value.some(p => p.id === view.provider)
  const provider = supported ? view!.provider : kind.value === 'asr' ? 'DOUBAO' : 'DASHSCOPE_CN'
  const available = catalog.value.filter(m => m.provider === provider)
  const model = kind.value === 'asr' ? available.find(m => supported && m.model === view?.model)?.model || available[0]?.model || '' : view?.model || 'qwen-plus'
  const selected = available.find(m => m.model === model)
  Object.assign(form, { mode: 'GLOBAL', provider, model, vocabularyId: supported ? view?.vocabularyId || '' : '', language: supported ? view?.language || 'zh' : 'zh', diarization: supported && view?.model === model ? !!view?.diarization : !!selected && selected.speakerMode !== 'NONE', reuseAsrKey: kind.value === 'llm' && !!view?.reuseAsrKey, workspaceId: supported ? view?.workspaceId || '' : '', appId: supported ? view?.appId || '' : '', apiKey: '', secretId: '', secretKey: '' })
  error.value = ''; notice.value = ''; result.value = undefined
}
async function load() { const [saved, supported] = await Promise.all([request<Record<Kind, View>>('/admin/ai', { cache: 'no-store' }), request<Model[]>('/admin/ai/models', { cache: 'no-store' })]); views.value = saved; catalog.value = supported; fill() }
function switchKind(value: Kind) { if (!busy.value) { kind.value = value; fill() } }
function changeProvider() {
  form.apiKey = ''; form.appId = ''; form.secretId = ''; form.secretKey = ''; form.reuseAsrKey = false; form.workspaceId = ''; form.vocabularyId = ''; form.language = 'zh'
  form.model = kind.value === 'asr' ? (models.value[0]?.model || '') : form.provider === 'DEEPSEEK' ? 'deepseek-chat' : 'qwen-plus'
  form.diarization = kind.value === 'asr' && canSeparate.value
}
watch(() => [form.provider, form.model, form.language], () => {
  if (kind.value === 'asr' && !canSeparate.value) form.diarization = false
  if (kind.value === 'asr' && capability.value?.speakerRequired) form.diarization = true
})
watch(form, () => { result.value = undefined; notice.value = '' })
async function save() {
  saving.value = true; error.value = ''
  try {
    const view = await request<View>(`/admin/ai/${kind.value}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form) })
    const persisted = await request<Record<Kind, View>>('/admin/ai', { cache: 'no-store' })
    confirmAiSave(view, persisted, kind.value)
    views.value = persisted
    form.model = view.model
    form.apiKey = ''; form.secretId = ''; form.secretKey = ''; await nextTick(); notice.value = '已保存并确认生效。所有用户与眼镜的新任务使用这份配置。'; emit('saved')
  } catch (e) { error.value = (e as Error).message }
  finally { saving.value = false }
}
async function test() {
  testing.value = true; error.value = ''; result.value = undefined
  const saved = views.value?.[kind.value]
  testedDraft.value = !!form.apiKey || !!form.secretId || !!form.secretKey || saved?.mode !== 'GLOBAL' || saved?.ready !== true || ['provider', 'model', 'vocabularyId', 'language', 'diarization', 'reuseAsrKey', 'workspaceId', 'appId'].some(key => form[key as keyof Input] !== saved?.[key as keyof View])
  try { result.value = await request<Result>(`/admin/ai/${kind.value}/test`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form) }, 65000) }
  catch (e) { error.value = (e as Error).message }
  finally { testing.value = false }
}
onMounted(async () => { try { await load() } catch (e) { error.value = (e as Error).message } finally { loading.value = false } })
</script>
<template>
  <div class="modal-backdrop" @click.self="!busy && emit('close')"><section class="ai-modal" role="dialog" aria-modal="true" aria-labelledby="ai-title">
    <button class="modal-close icon-button" aria-label="关闭 AI 设置" :disabled="busy" @click="emit('close')"><X :size="20" /></button>
    <span class="eyebrow">SERVICE SETTINGS</span><h2 id="ai-title">AI 服务设置</h2><p class="ai-intro">由管理员统一配置。普通用户和眼镜直接使用，无需填写 Key。</p>
    <div class="ai-access"><ShieldCheck :size="18" /><span>Key 加密保存在服务端，保存后不会返回浏览器。</span></div>
    <div class="ai-tabs"><button :class="{ active: kind === 'asr' }" :disabled="busy" @click="switchKind('asr')"><Radio :size="17" />实时转录<span :class="['ai-status', { ready: views?.asr.ready }]">{{ views?.asr.ready ? '已配置' : '待配置' }}</span></button><button :class="{ active: kind === 'llm' }" :disabled="busy" @click="switchKind('llm')"><FileText :size="17" />会议纪要<span :class="['ai-status', { ready: views?.llm.ready }]">{{ views?.llm.ready ? '已配置' : '待配置' }}</span></button></div>
    <p v-if="!loading && views?.[kind]" class="ai-intro">当前生效：{{ providers.find(p => p.id === views?.[kind].provider)?.name || views[kind].provider }} · {{ views[kind].model }}。下面填写的更改在保存后生效。</p><p v-if="kind === 'asr' && views?.asr.provider === 'TENCENT'" class="ai-intro">当前腾讯配置此前连接返回额度不足，尚未验证成功，已从可选列表移除。原凭证保留，切换豆包前不会自动更换。</p><p v-if="kind === 'asr' && form.provider === 'DOUBAO'" class="ai-intro">豆包是本次新接入，协议测试通过不等于真实效果通过。填写语音控制台 API Key，测试并保存后，再用眼镜多人录音验证。</p>
    <div v-if="loading" class="ai-loading">正在读取服务配置…</div>
    <form v-else @submit.prevent="save">
      <div v-if="error" class="alert" role="alert">{{ error }}</div>
      <fieldset :disabled="busy" class="ai-fields">
        <div class="ai-form-grid"><label>服务商与地域<select v-model="form.provider" @change="changeProvider"><option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.name }}</option></select></label><label>{{ form.provider === 'DOUBAO' ? '模型与识别模式' : '模型' }}<select v-if="kind === 'asr'" v-model="form.model"><option v-for="model in models" :key="model.model" :value="model.model">{{ model.label }}</option></select><input v-else v-model="form.model" required maxlength="128" list="llm-models" /><small>{{ kind === 'asr' ? capability?.note || '该模型的能力尚未验证，请选择已接入的模型。' : '填写 API 模型 ID，注意大小写；例如 qwen3.7-flash。' }}</small></label></div>
        <p v-if="kind === 'asr'" class="ai-intro" role="status">所选模型能力：{{ capability?.partialResults ? '逐字实时输出' : '按句返回' }} · {{ canSeparate ? (capability?.speakerMode === 'REALTIME' ? '支持实时分人' : '支持会议中句级分人') : '不支持分人' }}<template v-if="form.provider === 'DOUBAO'"> · {{ capability?.twoPass ? '二遍精校' : '单遍识别' }}。使用豆包语音 2.0 小时版；官方参考 1 元/音频小时，未单列二遍加价。</template></p>
        <datalist id="asr-models"><option v-for="model in models" :key="model.model" :value="model.model">{{ model.label }}</option></datalist><datalist id="llm-models"><template v-if="form.provider.startsWith('DASHSCOPE')"><option value="qwen3.7-flash" /><option value="qwen-plus" /><option value="qwen-max" /></template><option v-if="form.provider === 'DEEPSEEK'" value="deepseek-chat" /></datalist>
        <label v-if="form.provider.startsWith('DASHSCOPE')">工作空间 ID <span class="ai-optional">可选</span><input v-model="form.workspaceId" maxlength="64" placeholder="填写控制台工作空间 ID，留空使用公共接入" /><small>Key、模型和工作空间必须属于所选地域。</small></label>
        <label v-if="kind === 'asr' && (form.provider.startsWith('DASHSCOPE') || form.provider === 'TENCENT')">热词表 ID <span class="ai-optional">可选</span><input v-model="form.vocabularyId" maxlength="128" placeholder="用于人名、产品名和专业术语" /></label>
        <template v-if="kind === 'asr' && capability">
          <label v-if="capability && capability.languages.length > 1">识别语言<select v-model="form.language"><option value="zh">中文</option><option value="en">英语</option><option value="multi">多语种（供应商支持的语言）</option><option value="ja">日语</option><option value="ko">韩语</option></select></label>
          <label class="ai-checkbox"><input v-model="form.diarization" type="checkbox" :disabled="!canSeparate || capability?.speakerRequired" />区分说话人<small>{{ canSeparate ? '姓名可在会议进行时点击修改，保存后立即生效；编号只用于本场会议。' : '当前模型或识别模式不支持分人；不会伪造说话人编号。' }}</small></label>
        </template>
        <label v-if="kind === 'llm' && form.provider.startsWith('DASHSCOPE')" class="ai-checkbox"><input v-model="form.reuseAsrKey" type="checkbox" />使用已保存的同地域转录 Key<small>仅适用于转录也使用百炼时；切换转录服务会保留原百炼纪要 Key。</small></label>
        <template v-if="form.provider === 'TENCENT'">
          <label>AppID<input v-model="form.appId" required inputmode="numeric" maxlength="20" placeholder="腾讯云账号的数字 AppID" /></label>
          <label>SecretId<input v-model="form.secretId" type="password" maxlength="256" autocomplete="new-password" placeholder="已保存凭证可留空；更换时同时填写两项" /></label>
          <label>SecretKey<input v-model="form.secretKey" type="password" maxlength="256" autocomplete="new-password" placeholder="填写腾讯云 SecretKey" /><small>两项密钥仅存服务端并加密保存。请在腾讯云开通 ASR，购买对应资源包或开启大模型 2.0 后付费。</small></label>
        </template>
        <label v-else-if="!form.reuseAsrKey">API Key<input v-model="form.apiKey" type="password" maxlength="2048" autocomplete="new-password" :placeholder="views?.[kind].keySet && views?.[kind].provider === form.provider ? '已保存 Key；留空保留，输入新 Key 替换' : '粘贴服务商 API Key'" /><small>Key 不会出现在会议、分享链接或眼镜端。切换服务商需要填写新 Key。</small></label>
      </fieldset>
      <div v-if="result" :class="['ai-test-result', { success: result.ok }]" role="status"><Check v-if="result.ok" :size="18" /><X v-else :size="18" /><div><strong>{{ result.ok ? (testedDraft ? '连接通过 · 尚未保存' : '已保存配置连接通过') : '连接测试失败' }} <span>{{ (result.elapsedMs / 1000).toFixed(1) }} 秒</span></strong><p>{{ result.message }}</p><p v-if="result.ok && testedDraft">请点击「保存配置」，确认标签变为「已配置」后再开始录音。</p></div></div>
      <p v-if="notice" class="ai-save-notice" role="status"><Check :size="16" />{{ notice }}</p>
      <div class="ai-footer"><p>测试当前填写内容，不会自动保存。<br />会发起真实云端短任务，可能产生少量调用费用。</p><div><button type="button" class="secondary" :disabled="busy" @click="test"><LoaderCircle v-if="testing" class="spin" :size="16" />{{ testing ? '正在测试…' : '测试连接' }}</button><button type="submit" class="primary" :disabled="busy"><LoaderCircle v-if="saving" class="spin" :size="16" />{{ saving ? '正在保存…' : '保存配置' }}</button></div></div>
      <a v-if="form.provider === 'TENCENT'" class="ai-doc-link" href="https://console.cloud.tencent.com/asr/settings" target="_blank" rel="noopener">腾讯云 ASR 服务与后付费设置 <ArrowUpRight :size="14" /></a>
      <a v-if="form.provider === 'TENCENT'" class="ai-doc-link" href="https://console.cloud.tencent.com/developer" target="_blank" rel="noopener">查看账号 AppID <ArrowUpRight :size="14" /></a>
      <a v-if="form.provider === 'TENCENT'" class="ai-doc-link" href="https://console.cloud.tencent.com/cam/capi" target="_blank" rel="noopener">获取腾讯云 AppID 与密钥 <ArrowUpRight :size="14" /></a>
      <a v-if="form.provider === 'DOUBAO'" class="ai-doc-link" href="https://console.volcengine.com/speech/new/setting/apikeys" target="_blank" rel="noopener">获取豆包语音 API Key <ArrowUpRight :size="14" /></a>
      <a v-if="kind === 'asr' && capability?.documentation" class="ai-doc-link" :href="capability.documentation" target="_blank" rel="noopener">查看所选模型能力文档 <ArrowUpRight :size="14" /></a>
      <a v-if="form.provider === 'SONIOX'" class="ai-doc-link" href="https://console.soniox.com" target="_blank" rel="noopener">获取 Soniox Key <ArrowUpRight :size="14" /></a>
      <a v-if="form.provider.startsWith('DASHSCOPE')" class="ai-doc-link" href="https://help.aliyun.com/zh/model-studio/get-api-key" target="_blank" rel="noopener">在百炼控制台获取 Key <ArrowUpRight :size="14" /></a>
    </form>
  </section></div>
</template>
