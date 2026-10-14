<script setup lang="ts">
import { computed } from 'vue'
import { renderSummary } from './summary-markdown.js'
const props = defineProps<{ text: string }>()
const rendered = computed(() => renderSummary(props.text))
</script>
<template><div class="summary-markdown" v-html="rendered"></div></template>
<style scoped>
.summary-markdown{font-size:14px;line-height:1.9;overflow-wrap:anywhere}
.summary-markdown :deep(h1),.summary-markdown :deep(h2),.summary-markdown :deep(h3){margin:22px 0 10px;font-size:18px;line-height:1.5}
.summary-markdown :deep(h1){font-size:22px}.summary-markdown :deep(p){margin:10px 0}.summary-markdown :deep(ul),.summary-markdown :deep(ol){padding-left:24px;margin:10px 0}
.summary-markdown :deep(table){border-collapse:collapse;width:100%;margin:15px 0;font-size:13px}
.summary-markdown :deep(th),.summary-markdown :deep(td){border:1px solid #e4e9f2;padding:9px;text-align:left;vertical-align:top}
.summary-markdown :deep(th){background:#f4f6fb}.summary-markdown :deep(blockquote){border-left:3px solid #c9d4f4;padding-left:12px;color:#64748b;margin:15px 0}
.summary-markdown :deep(a){color:#3458db;text-decoration:underline}.summary-markdown :deep(pre){white-space:pre-wrap}.summary-markdown :deep(hr){border:0;border-top:1px solid #e4e9f2;margin:20px 0}
</style>
