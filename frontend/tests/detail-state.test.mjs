import {test} from 'node:test';import assert from 'node:assert/strict';
import {detailNeedsLive,defaultDetailTab} from '../src/detail-state.js';
import {renderSummary} from '../src/summary-markdown.js';
const detail=(status,summaryStatus='DONE',summary='## 摘要\n内容')=>({meeting:{status,summaryStatus,summary}});
test('completed details are static; active work still receives finite updates',()=>{
 for(const status of ['COMPLETED','INTERRUPTED','FAILED'])assert.equal(detailNeedsLive(detail(status)),false);
 for(const status of ['WAITING','CONNECTING','RECORDING','FINALIZING','REPROCESSING'])assert.equal(detailNeedsLive(detail(status)),true);
 assert.equal(detailNeedsLive(detail('COMPLETED','RUNNING')),true);
 assert.equal(defaultDetailTab(detail('COMPLETED','RUNNING','')),'summary');assert.equal(defaultDetailTab(detail('COMPLETED')),'summary');assert.equal(defaultDetailTab(detail('RECORDING')),'transcript');
});
test('summary renders headings and action tables without executing embedded HTML or fetching images',()=>{
 const html=renderSummary('## 待办\n\n|任务|负责人|\n|---|---|\n|检查|甲|\n\n<script>alert(1)</script>\n\n![image](https://example.com/tracker)\n\n[x](javascript:alert(1))');
 assert.ok(html.includes('<h2>待办</h2>'));assert.ok(html.includes('<table>'));
 assert.ok(!html.includes('<script>'));assert.ok(!html.includes('<img'));assert.ok(!html.includes('href="javascript:'));
});
test('local queued jobs keep details updated and stop when done',()=>{
 for(const status of ['QUEUED','RUNNING','RETRY'])assert.equal(detailNeedsLive({...detail('COMPLETED','IDLE',''),localTranscription:{status}}),true);
 for(const status of ['DONE','FAILED','EMPTY','SKIPPED'])assert.equal(detailNeedsLive({...detail('COMPLETED','IDLE',''),localTranscription:{status}}),false);
});
