import { test } from 'node:test'
import assert from 'node:assert/strict'
import { confirmAiSave } from '../src/ai-save.js'
const saved={mode:'GLOBAL',ready:true,updatedAt:'2026-10-13T10:44:00Z',provider:'DASHSCOPE_CN',model:'fun-asr-realtime'}
test('save requires an independent matching read of the persisted configuration',()=>{
 const read={asr:{...saved},llm:{...saved,model:'qwen-plus'}}
 assert.equal(confirmAiSave(saved,read,'asr'),read)
})
test('a 200 response with missing configuration cannot report save success',()=>{
 assert.throws(()=>confirmAiSave({}, {asr:saved},'asr'))
 assert.throws(()=>confirmAiSave(saved, {},'asr'))
})
test('an environment fallback or unready write cannot report global save success',()=>{
 for(const change of [{mode:'ENVIRONMENT'},{ready:false},{updatedAt:null}]) {
  assert.throws(()=>confirmAiSave({...saved,...change},{asr:saved},'asr'))
  assert.throws(()=>confirmAiSave(saved,{asr:{...saved,...change}},'asr'))
 }
})
test('a stale read cannot confirm a new write',()=>{
 assert.throws(()=>confirmAiSave(saved,{asr:{...saved,updatedAt:'2026-10-13T10:00:00Z'}},'asr'))
})
test('a concurrent provider or model change must be reported instead of false success',()=>{
 for(const change of [{provider:'DASHSCOPE_INTL'},{model:'paraformer-realtime-v2'}])
  assert.throws(()=>confirmAiSave(saved,{asr:{...saved,...change}},'asr'))
})
