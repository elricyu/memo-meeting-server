# Memo Meeting 接口与实时音频协议 v1

状态：已实现，并通过隔离自动联调。版本：`1`。账号与设备认证以 [硬件绑定协议](device-binding-v1.md) 为准，取代本文件早期无需登录的草案。所有 JSON 使用 UTF-8；所有转录时间使用毫秒。网页和 RV1106B 客户端使用相同业务协议。API Key 只由后端访问云端服务。

## 1. 参考 Scriberr 的范围

参考代码：当前项目 `scriberr/internal/api/router.go`、`handlers.go` 的 `UploadAudio`、`web/frontend/src/components/AudioRecorder.tsx`。

| Scriberr 行为 | 本服务行为 |
| --- | --- |
| `/api/v1/transcription/upload` 上传完整音频时创建 job | `/api/v1/meetings` 在录音前创建会议记录，查看端立即可见 |
| `/:id/start`、`/:id/status`、`/:id/transcript` | 分离会议资源、录音会话和转录结果；提供 status/transcript 读取接口 |
| `/:id/audio` 支持回听 | 完成或中断后读取 WAV，可使用 HTTP Range |
| 先完整上传，再执行转录和摘要 | WebSocket 持续传 PCM，SSE 持续显示草稿与已确认结果，结束后单独生成纪要 |

新协议不声称兼容 Scriberr API。保留 `/api/*` 和 `/ws/record/{id}` 是本项目初版客户端的过渡兼容路径，后者发送裸 PCM；新的客户端必须使用下述 v1 路径和帧头。不会自动探测并混用两种音频协议。

## 2. 业务链路

1. 网页用户登录，或眼镜完成硬件绑定；记录端使用登录 Cookie/设备 Bearer 凭证通过 POST 创建会议，拿到会议 ID 和可选录音凭证。
2. 查看端 GET 列表，或订阅列表 SSE；创建完成的 WAITING 会议立即出现。
3. 记录端打开音频 WebSocket，发送 start 握手。
4. 后端确认权限、独占录音会话后返回 ready（录音默认不连接云端识别）。
5. 记录端读取 ready.offsetBytes，按顺序发送 MM01 音频帧。后端保存音频；用户主动开启实时转录后才转发新增音频到云端。
6. 查看端独立订阅详情 SSE，获取实时草稿与已确认句子，不参与音频上传。
7. 记录端 flush 最后一个短帧，发送 stop，保持连接，等待 finished。
8. 后端收到云端最后一句和 task-finished 后刷盘、保存 COMPLETED 并返回 finished。

没有云端 Key 时 config.asrReady=false，创建记录仍可用；录音可正常保存；主动转录明确拒绝，不返回虚假转录。

## 3. 创建会议

`POST /api/v1/meetings`，Content-Type: application/json。

```json
{
  "title": "产品周会",
  "context": "Memo Meeting、张明、实时语音识别",
  "source": "WEB",
  "clientRequestId": "9d61d042-7c67-49f2-981e-9658dcb30bc2",
  "recorderToken": "<客户端用 CSPRNG 产生的 32 字节随机数，以 64 位 hex 表示>"
}
```

- title 必填，trim 后 1–200 字；context 可选，最多 400 字，默认空字符串。
- source 可选，网页只能选 WEB / OTHER，默认 WEB；已绑定设备由后端强制设置 GLASSES，不接受客户端指定 ownerId/deviceId。
- clientRequestId 可选 UUID；使用它时必须提供 recorderToken。建议客户端先生成并暂存二者。
- recorderToken 可选，提供时长度 32–256 字符，必须使用密码学随机数。省略时后端生成，但丢失创建响应后无法重取该凭证。
- 同一 clientRequestId、相同 title/context/source/token 重试返回同一记录；同一请求 ID 内容或凭证不同返回 409。仅 title/context/source 比较 trim 后内容。
- token 的 SHA-256 存于数据库；列表、详情、日志、SSE 不含 token。创建响应只将本次请求携带或新生成的 token 返回给创建端。

成功 HTTP 200（新建与幂等重试相同）：

```json
{
  "meeting": {
    "id": "<UUID>",
    "title": "产品周会",
    "context": "Memo Meeting、张明、实时语音识别",
    "source": "WEB",
    "status": "WAITING",
    "createdAt": "2026-10-13T09:00:00Z",
    "updatedAt": "2026-10-13T09:00:00Z",
    "audioBytes": 0,
    "ownerId": 101,
    "deviceId": null,
    "error": "",
    "summary": "",
    "summaryStatus": "IDLE",
    "summaryError": ""
  },
  "recorderToken": "<仅创建端保管>",
  "protocolVersion": 1,
  "audioSpec": {
    "format": "pcm_s16le", "sampleRate": 16000, "channels": 1,
    "bitsPerSample": 16, "frameHeaderBytes": 16,
    "recommendedChunkMs": 100, "maxChunkBytes": 32000
  }
}
```

查看链接：`/?meeting={id}`。必须登录记录所属的同一用户，不支持凭链接匿名查看。所有读取/写操作校验 ownerId；眼镜凭证进一步限制为本设备创建的记录。

## 4. 录音 WebSocket

`ws(s)://<服务地址>/ws/v1/meetings/{id}/audio`。浏览器自动带登录 Cookie，并遵循服务端 ALLOWED_ORIGINS；眼镜原生客户端必须带 `Authorization: Bearer <deviceToken>`，可以不发送 Origin。服务端限制文本 4096 字节、二进制 32016 字节。建连 10 秒内必须发送 start。

客户端 start：

```json
{
  "type": "start", "protocolVersion": 1,
  "token": "<recorderToken>",
  "audio": { "format": "pcm_s16le", "sampleRate": 16000, "channels": 1, "bitsPerSample": 16 }
}
```

start.token 可省略；如提供则必须正确，它不能代替用户/设备认证。固定音频参数；客户端负责重采样。不接受 WebM/Opus/MP3、WAV 文件头或 base64 文本音频。每条连接只发送一次 start，同一会议最多一个记录端。

服务端 ready（必须收到后才开始采集和传输）：

```json
{
  "type": "ready", "protocolVersion": 1,
  "sessionId": "<UUID>", "offsetBytes": 0,
  "audioSpec": { "format": "pcm_s16le", "sampleRate": 16000, "channels": 1, "bitsPerSample": 16, "frameHeaderBytes": 16, "recommendedChunkMs": 100, "maxChunkBytes": 32000 }
}
```

每条 binary message 是一个 MM01 帧：

| 字节位置 | 长度 | 含义 |
| --- | --- | --- |
| 0 | 4 | ASCII MM01，十六进制 4d 4d 30 31 |
| 4 | 4 | uint32 LE sequence，本次连接从 0 开始，每帧 +1 |
| 8 | 8 | uint64 LE offsetBytes，本帧 PCM 在完整会议音频中的起始字节偏移 |
| 16 | 2–32000 | PCM16LE 载荷，偶数字节；建议 3200 字节，即 100ms |

- 第一帧 offsetBytes 必须等于 ready.offsetBytes；下一帧 offset = 上一帧 offset + 上一帧 PCM 长度。
- sequence 与 offset 均严格连续；重复帧、跳号、偏移错误、错误格式会明确关闭连接并中断记录，不静默补音频。
- 偏移只计算 PCM，不含 MM01 头和保存文件中的 44 字节 WAV 头。
- 16kHz × 1ch × 16bit = 32000 bytes/s，durationMs = audioBytes / 32。

保存确认：每累计约 1 秒 PCM 刷盘并更新数据库，服务端返回：

```json
{ "type": "ack", "sessionId": "<UUID>", "sequence": 9, "committedBytes": 32000 }
```

ack 表示这些 PCM 已完成文件 fsync 和数据库字节数更新，不表示它们已识别完。客户端可以释放 committedBytes 之前的本地重发缓冲。没有 ack 的最后一段可能已经写入；重连时以新的 ready.offsetBytes 为准。不要根据“WebSocket send 返回”判断后端已保存。

心跳：客户端每 15 秒发送 `{"type":"ping"}`；服务端返回 `{"type":"pong"}`。持续无客户端音频或 ping 超过 60 秒，记录中断。

停止：发送所有音频（含最后一个不足 100ms 的短帧）后发送 `{"type":"stop"}`。服务进入 FINALIZING，仍会推送最后一句。客户端等待：

```json
{ "type": "finished", "sessionId": "<UUID>", "committedBytes": 35200, "message": "" }
```

最后一次 ack 与 finished 在最终刷盘后发送，随后正常关闭 WebSocket。不要在 stop 后立即关闭连接。尾句确认上限 25 秒；转录尾句失败不影响音频完成保存，可用已保存音频重新转录。音频为空时不发送 ack，finished 的 committedBytes 为 0。

错误：`{"type":"error","code":"INVALID_PROTOCOL|RECORDING_REJECTED|RECORDING_INTERRUPTED","message":"可显示给用户的原因"}`。已开始的会话错误通常附带 sessionId/committedBytes；关闭码 1008 表示协议/权限拒绝。成功完成关闭码 1000。云端服务异常会明确中断，不自动重试云端付费任务。

## 5. 查看端 SSE

| 接口 | 事件 | 含义 |
| --- | --- | --- |
| GET /api/v1/events | snapshot | 首次订阅就绪：`{"ready":true}` |
| 同上 | changed | 新建或状态/进度变化：`{"id":"<meetingId>"}`，查看端刷新列表 |
| GET /api/v1/meetings/{id}/events | snapshot | 完整详情，包括 meeting、segments、draft |
| 同上 | progress | `{"audioBytes":32000,"durationMs":1000}`，更新时长 |
| 两者 | heartbeat | 每 15 秒 `{"time":<Unix毫秒>}` |

详情 snapshot 示例：

```json
{
  "meeting": { "id":"<UUID>", "status":"RECORDING", "audioBytes":32000 },
  "segments": [{ "id":"<sessionUUID>:1", "startMs":100, "endMs":950, "text":"确认后的第一句话。" }],
  "draft": "仍在识别的下一句话"
}
```

meeting 实际包含第 3 节全部字段及 ownerId/deviceId。segments 仅包含云端句末确认过的结果，按开始时间排序；id 唯一，重复结果执行 upsert。draft 可被多次整体替换，不要 append；它不写入最终稿。收到 snapshot 替换详情，不要累加，否则会出现重复句子。

SSE 使用 EventSource 自动重连；重连立即发送当前完整 snapshot，不依赖 Last-Event-ID 和历史事件回放。浏览器断开只影响查看，不会停止记录端音频。反向代理必须关闭 SSE 缓冲和压缩，维持长连接。

## 6. 状态与续录

```text
WAITING → CONNECTING → RECORDING → FINALIZING → COMPLETED
             ↓            ↓           ↓
          INTERRUPTED ← 云端/设备断线/服务停止/尾句确认失败
             ↓
          CONNECTING → RECORDING（重新建连，同一会议继续）
COMPLETED/INTERRUPTED/FAILED → REPROCESSING → COMPLETED
```

- CONNECTING/RECORDING/FINALIZING/REPROCESSING 在服务重启后变为 INTERRUPTED。
- 中断时保留确认文字和已接收 WAV，draft 不视为确认内容。
- 续录建立新 sessionId，sequence 从 0，offsetBytes 接续 WAV 的真实长度；转录时间以现有 PCM 时长为基准。
- 第一版时间轴是已保存音频时间轴，不把断网期间没有收到的现实时间拼进 WAV。不存在“无损断网采集”的隐含承诺；采集端若要补交离线音频，需要独立导入协议，当前未提供该接口。
- COMPLETED 不可继续追加，避免修改已完成记录。INTERRUPTED/FAILED 可续录，或显式结束。
- REPROCESSING 按保存的完整音频约实时速度重新调用云端 ASR；结束后原子替换确认文字并失效旧纪要。失败保留旧原文和旧纪要。

## 7. 其他 REST 接口

| 方法 | 路径（前缀 /api/v1） | 响应 / 约束 |
| --- | --- | --- |
| GET | /config | 云端配置就绪状态、模型、时长上限、音频协议；不含 Key |
| GET | /health | `{status:ok}`，代表本地服务存活，非云端可用性证明 |
| GET | /meetings | 最近 500 条，创建时间降序 |
| GET | /meetings/{id} | 完整详情，与 SSE snapshot 相同 |
| GET | /meetings/{id}/status | meeting 对象 |
| GET | /meetings/{id}/transcript | segments + draft |
| POST | /meetings/{id}/finish | 结束 WAITING/INTERRUPTED/FAILED，返回完整详情 |
| POST | /meetings/{id}/retranscribe | 返回 `{accepted:true}`；异步任务，订阅详情观察完成 |
| POST | /meetings/{id}/summary | 返回 `{accepted:true}`；summaryStatus: IDLE/RUNNING/DONE/FAILED |
| GET | /meetings/{id}/audio | audio/wav，未结束录音或重转录时 409，没有音频 404 |
| GET | /meetings/{id}/export | Markdown 附件，纪要及带时间戳原文 |

上述三个 POST 写操作必须为会议所属用户或本设备身份。浏览器须带 `X-XSRF-TOKEN`，眼镜须带 Bearer deviceToken。`X-Recorder-Token` 为可选附加校验，提供时必须正确；它不能绕过归属鉴权。运行中的录音/重转录/纪要任务对同一会议互斥；错误 token 不改变记录状态。不支持删除、跨记录合并或离线文件导入。

用户/设备身份未认证为 401，请求过于频繁为 429。HTTP 错误体固定为 `{"message":"可显示的错误原因"}`：400 参数错误，403 写权限错误，404 记录不存在，409 状态/并发/幂等冲突，503 云端 Key 未配置，500 本地内部错误。任何读取不存在会议的接口都必须先校验记录存在。

## 8. 客户端最小伪代码

```js
const ws = new WebSocket('/ws/v1/meetings/' + id + '/audio')
ws.onopen = () => ws.send(JSON.stringify({type:'start',protocolVersion:1,token,audio:{format:'pcm_s16le',sampleRate:16000,channels:1,bitsPerSample:16}}))
// 收到 ready 后：seq=0; offset=ready.offsetBytes; 开始采集
// 每帧：发送 [MM01][seq:u32le][offset:u64le][pcm]；seq++; offset+=pcm.length
// ack：记下 committedBytes
// 停止：flush PCM → 发送 stop → 等 finished → 关闭采集资源
// 断线：展示中断 → 若用户继续，重新握手并使用新的 offsetBytes
```

二进制参考实现：`frontend/public/audio-frame.js` 与 `backend/.../AudioProtocol.java`。机器可读 REST 定义见 `openapi.json`；WebSocket 与 SSE 的时序、保存语义以本文件为准。


## 9. 实时分人、姓名编辑与完成态

`segments[].speakerId` 为本场会议内的稳定编号，`speakers[]` 为编号和显示姓名的映射。未返回编号时保持未知，不按文字猜人。Soniox 使用 `stt-rt-v5`、中文提示、16 kHz PCM 和实时分人；不开启提前强制断句，以保留分人所需上下文。最终 token 追加同一发言段，草稿为替换式临时尾部，停止时等待云端 `finished` 并保存尾句。Deepgram 使用 Nova-3 和 `diarize_model=latest`。

会议所属网页登录后调用 `POST /meetings/{id}/speakers/{speakerId}`，请求 `{name:"张三"}`。允许在录音期间修改，保存后返回完整详情并向该会议 SSE 广播 snapshot。同一编号的已有、后续原文和导出立即使用新姓名；录音会话及 PCM 序号不改变。重转录或纪要生成期间返回 409，避免更改其输入快照。改名失效旧纪要，用户可重新生成；重新转录建立新编号映射。该映射不承诺跨会议识别人名。

完成且无后台处理时，网页使用 GET 快照并关闭会议详情 SSE，默认打开已生成纪要。全局列表事件仍用于通知用户的记录变化；若对所选会议收到变化，才重新读取详情。主动生成纪要或重转录时重新开启有限期的详情订阅，任务结束后关闭。

`POST /meetings/{id}/transcript/exclusions` 请求 `{segmentIds:["确认的片段 ID"],reason:"设备播报"}`。仅允许所属用户/本设备在录音与纪要处理结束后操作；保留原文数据库行和原 WAV 备份，隐藏指定片段，并将对应 PCM 区间静音。时间轴和音频长度保持原值，不用关键词自动删除正常讨论。眼镜的新录音在采集上传前按物理按键与固件播放状态过滤，保留短缓冲以覆盖状态通知延迟；状态观察中断即停止录音。


腾讯云 V2 新增供应商 `TENCENT`，固定模型 `16k_zh_en_speaker_2.0`。管理员配置 AppID、SecretId、SecretKey；两个 Secret 字段仅用于输入，读回只有 AppID、就绪状态、已保存标记。凭证加密后存入已有全局密钥表，不下发采集端。后端以 V2 URL及排序参数生成 HMAC-SHA1 签名，code=0 就绪后透传 PCM；以 `{type:"end"}` 结束并等待 final=1。sentence_list 为累计快照，以 sentence_id 更新，忽略未变化确认句，speaker_id=-1 不建伪说话人，后续标签补齐时更新原句。姓名映射与前述接口相同。


### ASR 模型能力（2026-10-14）

管理员 GET `/api/v1/admin/ai/models` 返回可选模型的 `provider/model/label/partialResults/twoPass/speakerMode/speakerRequired/languages/note/documentation`。`speakerMode` 为 `NONE`、`REALTIME` 或 `STREAMING_SENTENCE`；不能根据服务商推定模型能力。配置视图的 `supportsDiarization` 按模型与语言计算；`/api/v1/config.asrCapabilities` 不含凭证。普通用户或设备不能访问管理员模型列表。

豆包 `DOUBAO` 的系统模式 ID `doubao-seed-asr-2.0-twopass` 对应 async 二遍接口、资源 `volc.seedasr.sauc.duration`；模型名在上游为 `bigmodel`。只需要管理员的 `apiKey`，不下发眼镜；全局保存和会议纪要原 Key 保护规则不变。上游 gzip 二进制帧仅由后端处理，眼镜和网页仍发送 MM01，协议无需改变。姓名接口及 snapshot 广播规则不变。

仅模拟测试通过的模型从可选列表移除，本轮新接入的豆包显式标记待实测。原来保存的不可选模型仍可读取，凭证不会被删除或暗中替换。两个单遍模式在内部保留适配器测试，不自动回退或自动二次请求。


### 纪要自动命名

POST `/api/v1/meetings/{id}/summary` 仍异步执行。最终 AI 请求同时生成主题标题与 Markdown 纪要，成功后一次更新 `meeting.title/summary/summaryStatus=DONE`，通过详情 snapshot 和列表 changed 事件即时刷新。标题单行、最多60字符，必须根据原文命名。响应格式无效或云端失败时保留原标题和纪要，`summaryStatus=FAILED`。重新生成纪要也会重新生成标题。原始创建标题独立保存以维持 clientRequestId 重试校验；不会因 AI 修改显示标题而导致原创建请求重试冲突。


### 正常录音结束后自动生成纪要

记录端停止后，后端等待 ASR 最终结果和 WAV 落盘，再由正常录音完成事件启动纪要与标题任务。网页和已绑定眼镜均适用，不依赖查看端在线或用户登录上下文；配置依据会议 ownerId 读取。`meeting.status=COMPLETED` 与 `summaryStatus=RUNNING` 可以同时存在。此时查看端保持有限 SSE，收到 DONE/FAILED 后恢复静态详情。重复完成事件不会重复收费调用，正常 STOP 以外的断线、撤销、服务重启、重新转录不自动触发。

纪要服务未配置或无确认原文时保留标题和录音，标记纪要失败并说明原因。任务失败可手动 POST summary 重试；成功后也可重新生成。系统不会在启动时批量为历史会议生成纪要。

## 按需转录

录音、详情 GET 和 SSE 订阅不触发识别费用。登录用户主动调用 `POST /api/v1/meetings/{id}/transcription/start`，仅会议 `RECORDING` 时允许；设备身份不能开启，重复开启返回409。仅识别开启后的音频，时间戳保留完整录音偏移。Detail 提供 `transcribing`、`transcriptionStarting`、`transcriptionError`。转录故障继续保存录音；停止、断线或续录后默认关闭。结束后主动调用现有 `retranscribe` 接口处理完整音频，网页按钮为「转录」。未产生文字的正常停止不自动生成纪要。
