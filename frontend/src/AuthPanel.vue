<script setup lang="ts">
import { ref } from 'vue'
import { AudioLines, ArrowRight, ShieldCheck, Radio } from 'lucide-vue-next'
import { request, type User } from './api'
const emit = defineEmits<{ authenticated: [user: User] }>()
const register = ref(false), username = ref(''), displayName = ref(''), password = ref(''), busy = ref(false), error = ref('')
async function submit() {
  busy.value = true; error.value = ''
  try {
    const result = await request<{ user: User }>(register.value ? '/auth/register' : '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: username.value, password: password.value, ...(register.value ? { displayName: displayName.value } : {}) }) })
    password.value = ''; emit('authenticated', result.user)
  } catch(e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
</script>
<template>
  <main class="auth-page"><section class="auth-story"><a class="brand" href="/"><span class="brand-mark"><AudioLines :size="23" /></span>Memo Meeting</a><div><span class="eyebrow">YOUR CONVERSATIONS, CONNECTED</span><h1>专注交流。<br />让记录自然发生。</h1><p>网页与眼镜，同一个工作空间。<br />从实时转录到会议纪要，重要讨论都有迹可循。</p><div class="auth-feature"><Radio :size="18" /><span>绑定你的眼镜，会议自动归入你的账号</span></div><div class="auth-feature"><ShieldCheck :size="18" /><span>你的会议记录，只在你的账号下可见</span></div></div><small>MEMO MEETING · 让讨论留下来</small></section><section class="auth-form-wrap"><div class="auth-form"><span class="eyebrow">{{ register ? 'CREATE YOUR WORKSPACE' : 'WELCOME BACK' }}</span><h2>{{ register ? '创建你的工作空间' : '登录你的工作空间' }}</h2><p>{{ register ? '创建账号后，即可绑定眼镜并开始会议记录。' : '登录后查看会议，或继续眼镜上的录音。' }}</p><div v-if="error" class="alert" role="alert">{{ error }}</div><form @submit.prevent="submit"><label>用户名<input v-model="username" required minlength="3" maxlength="40" pattern="[A-Za-z0-9_]{3,40}" autocomplete="username" placeholder="3–40 位字母、数字或下划线" /></label><label v-if="register">显示名称<input v-model="displayName" required maxlength="60" autocomplete="name" placeholder="你的名字" /></label><label>密码<input v-model="password" type="password" required :minlength="register ? 8 : 1" maxlength="72" :autocomplete="register ? 'new-password' : 'current-password'" :placeholder="register ? '至少 8 位密码' : '输入你的密码'" /></label><button class="primary" :disabled="busy">{{ busy ? '正在处理…' : register ? '创建账号' : '登录' }}<ArrowRight :size="17" /></button></form><div class="auth-switch">{{ register ? '已有账号？' : '还没有账号？' }}<button :disabled="busy" @click="register = !register; error = ''">{{ register ? '登录' : '创建账号' }}</button></div></div></section></main>
</template>
