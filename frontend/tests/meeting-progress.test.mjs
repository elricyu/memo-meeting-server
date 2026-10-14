import { test } from 'node:test'
import assert from 'node:assert/strict'
import { meetingProgress } from '../src/meeting-progress.js'
const meeting = (overrides = {}) => ({ status: 'COMPLETED', transcriptCount: 0, summary: '', summaryStatus: 'IDLE', ...overrides })
test('completed meetings show each missing result independently', () => {
  assert.deepEqual(meetingProgress(meeting()).map(l => l.text), ['未转录', '未生成纪要'])
  assert.deepEqual(meetingProgress(meeting({ transcriptCount: 2 })).map(l => l.text), ['未生成纪要'])
  assert.deepEqual(meetingProgress(meeting({ transcriptCount: 2, summary: '纪要', summaryStatus: 'DONE' })), [])
  assert.deepEqual(meetingProgress(meeting({ status: 'RECORDING' })), [])
})
test('running and failed summaries retain their actual state, including retries with an old summary', () => {
  assert.deepEqual(meetingProgress(meeting({ transcriptCount: 1, summaryStatus: 'RUNNING' })).map(l => l.text), ['纪要生成中'])
  assert.deepEqual(meetingProgress(meeting({ transcriptCount: 1, summaryStatus: 'FAILED' })).map(l => l.text), ['纪要生成失败'])
  assert.deepEqual(meetingProgress(meeting({ transcriptCount: 1, summary: '旧纪要', summaryStatus: 'FAILED' })).map(l => l.text), ['纪要更新失败'])
  assert.deepEqual(meetingProgress(meeting({ transcriptCount: 1 }), 0).map(l => l.text), ['未转录', '未生成纪要'])
})
