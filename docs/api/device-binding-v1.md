# 用户账号与 RV1106B 硬件绑定协议 v1

状态：后端与网页已实现，并通过隔离自动联调。本文与 protocol-v1.md 共同组成正式接口契约。本轮新增账号体系后，前期“受信任工作空间、仅录音凭证鉴权”的草案已被替代；所有会议读取和写操作均校验当前用户归属。

## 1. 固定流程

```text
网页用户注册/登录
       │
眼镜生成随机 deviceToken，携带真实 clientId + MAC 发起绑定
       │
后端生成 5 分钟有效的四位数字验证码（保留前导零）
       │
眼镜逐位播报验证码，过期自动续期并播报新码
       │
用户在网页「我的眼镜」输入验证码 → 点击绑定
       │
后端写入 device.ownerId，绑定码一次性消费
       │
眼镜轮询得知 BOUND，自动继续开启请求；用 Bearer deviceToken 创建会议及上传音频
       │
会议 ownerId 由后端从绑定关系得出 → 只在对应用户的列表中出现
```

用户不能通过请求体指定 ownerId。眼镜不保存用户密码、浏览器会话或云端 Key；使用自己的随机凭证。clientId/MAC 是设备上报的标识，不能单独用于认证。

## 2. 账号

前缀 `/api/v1/auth`，所有成功响应 HTTP 200。

| 方法 | 路径 | 请求 / 响应 |
| --- | --- | --- |
| GET | /csrf | `{token:"..."}`，并设置 XSRF-TOKEN Cookie |
| POST | /register | `{username,displayName,password}` → `{user:{id,username,displayName}}`，自动登录 |
| POST | /login | `{username,password}` → `{user:...}` |
| GET | /me | 已登录 `{user:...}`，未登录 `{user:null}`；设备身份不作为网页用户返回 |
| POST | /logout | `{loggedOut:true}`，使当前服务器会话失效 |

username 为 3–40 位字母、数字或下划线，不区分大小写；displayName 1–60 字；密码至少 8 字符，UTF-8 最多 72 字节，使用 BCrypt 哈希。没有默认用户或默认密码。

浏览器认证使用 HttpOnly JSESSIONID Cookie，SameSite=Lax。注册、登录和所有浏览器 POST 必须先 GET /csrf，并把返回 token 放在 X-XSRF-TOKEN 请求头。生产 HTTPS 设置 SESSION_COOKIE_SECURE=true。账号与密码哈希持久化到 H2；进程重启后网页需重新登录，设备绑定不会丢失。

设备使用 Authorization: Bearer <deviceToken>，不使用浏览器 Cookie，也不需要 CSRF 请求头。设备绑定发起接口允许未登录调用；它只创建待确认请求，不能创建会议。登录/注册、发起绑定和尝试输入绑定码有一分钟请求频率限制，超过限制 HTTP 429。

## 3. 眼镜发起绑定

`POST /api/v1/devices/pairings`，无需登录，JSON：

```json
{
  "clientId": "<眼镜的稳定客户端标识>",
  "mac": "AA:BB:CC:DD:EE:FF",
  "displayName": "我的 RV1106B 眼镜",
  "platform": "RV1106B",
  "deviceToken": "<CSPRNG 产生的 32 随机字节，64 位小写 hex>",
  "clientRequestId": "<本次绑定请求 UUID>"
}
```

- clientId 必填，1–128 字符。必须是设备上稳定、可持久保存的标识，重试不能随机变化。
- mac 必填，6 组冒号分隔十六进制，服务端校验并规范成大写；clientId 和 MAC 都持久化并在绑定确认页与设备列表显示。
- 采集哪个网卡的 MAC 由眼镜客户端选定并保持一致，推荐当前联网 Wi-Fi 接口的真实设备 MAC；不要传本机电脑或占位 MAC。
- displayName 1–64 字符，platform 1–32 字符。
- deviceToken 必須用密码学随机数生成，与 clientId/MAC 不同，设备自行安全保存。后端仅保存其 SHA-256，永不把它返回网页、列表或日志。
- clientRequestId 必填。同一请求 ID 和完全相同的设备信息/凭证重试，返回同一绑定请求；不同内容 409。
- 同一 clientId 或同一 MAC 同时只能有一个有效待绑定/已绑定设备，数据库唯一约束同时保护并发申请。未绑定申请过期后，可用原 token、原请求 ID 和完全相同的设备信息续期；解绑后需生成新 token 与新请求 ID。

响应：

```json
{
  "pairingId": "<UUID>",
  "deviceId": "<UUID，后端记录 ID>",
  "pairingCode": "0382",
  "expiresAt": "2026-10-13T10:05:00Z",
  "pollIntervalSeconds": 3
}
```

验证码固定为四位数字字符串，保留前导零。默认有效期 5 分钟，不是后续录音凭证。有效期间重试返回同一码；过期后同一请求续期返回新码并使旧码失效，眼镜自动逐位播报新码。刚失效的码保留冷却时间，避免迟到输入误绑定其他眼镜。数据库锁与唯一约束保护并发分配。

## 4. 眼镜轮询

`GET /api/v1/devices/pairings/{pairingId}`，必须带 `Authorization: Bearer <本次 deviceToken>`，建议每 3 秒一次。

```json
{"status":"PENDING","deviceId":"<UUID>"}
```

网页验证码校验并绑定成功后：

```json
{"status":"BOUND","deviceId":"<UUID>","owner":{"id":101,"displayName":"张明"}}
```

其他状态 EXPIRED / REVOKED。无效凭证或未知 pairingId 返回 404。BOUND 时不再下发新的明文凭证，眼镜使用发起绑定时已持有的 deviceToken。解绑后原轮询状态为 REVOKED，原凭证也不能再访问会议 API。

## 5. 网页检查与确认

以下接口必须为已登录的用户身份，设备身份不能调用。

`GET /api/v1/devices/pairings/preview?code=0382`：验证有效绑定码并返回待绑定设备信息。

```json
{
  "id":"<deviceId>",
  "clientId":"<眼镜客户端 ID>",
  "mac":"AA:BB:CC:DD:EE:FF",
  "displayName":"我的 RV1106B 眼镜",
  "platform":"RV1106B",
  "status":"PENDING",
  "createdAt":"2026-10-13T10:00:00Z"
}
```

用户确认：`POST /api/v1/devices/bind`，JSON `{"code":"0382"}`。成功返回 Device，status=BOUND。ownerId 取当前登录用户。已使用、过期或错误码返回 404；并发抢绑导致状态变化返回 409。网页输入验证码后直接绑定到当前登录账号，无需二次确认；名称、clientId 和 MAC 在已绑定设备列表显示。校验、绑定和验证码失效在同一数据库事务内完成，同时提交仅一方成功，以服务端完成事务为准。绑定成功后立即将验证码有效期结束。尝试校验按账号（10 次/分钟）和 IP（20 次/分钟）限速，preview 与 bind 共用额度。

## 6. 设备与会议归属

`GET /api/v1/devices`：当前用户已绑定设备数组，字段同 Device；不返回 deviceToken/hash。其他用户看不到这些设备。

已绑定眼镜携带 Bearer deviceToken 调用 `POST /api/v1/meetings`，无需传 ownerId/deviceId/source，后端自动设置：

```json
{"ownerId":101,"deviceId":"<deviceId>","source":"GLASSES"}
```

网页创建则 ownerId 为当前用户、deviceId 为 null、source 为 WEB（可选 OTHER）。网页请求冒充 GLASSES 来源被拒绝。网页用户可查看自己账号全部会议；眼镜凭证仅能读取/操作本设备创建的会议，不能访问同用户的其他设备或网页会议，也不能管理账号/绑定关系。

音频 WebSocket 握手时也必须携带同一设备 Bearer 凭证；JSON start 和 MM01 二进制帧仍按 protocol-v1.md。浏览器则自动使用登录 Cookie。

会议列表、详情、转录、SSE、WAV 下载、Markdown 导出和写操作全部校验 ownerId。不属于当前身份的会议统一返回 404。分享 `/?meeting={id}` 不会绕过登录，另一台设备必须登录同一用户。SSE 列表变化按用户独立广播，不跨用户泄露记录 ID。

## 7. 解绑与重新绑定

`POST /api/v1/devices/{deviceId}/unbind`，只允许绑定用户，浏览器需 CSRF。

- 返回 `{"unbound":true}`。
- 立即将设备状态持久化为 REVOKED，使原 Bearer token 失效。
- 停止该设备正在上传的录音，保留已收到音频，会议状态为 INTERRUPTED。
- 关闭该设备已有的查看订阅。
- 历史会议的 ownerId 永远不随设备重新绑定转移。
- 设备使用新 deviceToken/clientRequestId 再次申请绑定，用户批准后后续会议属于新用户；新用户仍不能读取旧用户的历史记录。

当前不支持免确认转移绑定、凭 MAC 自动接管、远程找回设备凭证或通过用户名直接绑定。

## 8. 数据

users：用户与 BCrypt 密码哈希。

devices：后端 id、client_id、mac、名称、平台、owner_id、token_hash、PENDING/BOUND/REVOKED、创建/更新时间、请求 ID。

pairings：请求 id、device_id、一次性 code、expires_at、PENDING/BOUND/EXPIRED。

meetings：增加 owner_id 与 device_id，后端赋值。原有未分配 ownerId 的记录仍保留在数据库和磁盘，普通用户不可读取，不自动归给第一个注册用户。

自动验证范围包括客户端 ID/MAC 保存、码过期、待绑定禁止创建会议、用户确认、重复消费、跨用户所有接口隔离、设备范围限制、解绑中断、原 token 失效、重新绑定和历史归属不变。

## 眼镜打开与录音反馈

用户主动说“打开会议记录”或“绑定会议账号”后，未绑定设备获取并播报验证码，轮询间隔 3 秒，过期使用同一申请续期并播报新码。单次等待最长 15 分钟，超时后需再次主动打开。绑定成功后继续创建会议和录音。已绑定设备直接创建会议和录音。两条路径均在音频源连接且服务端 WebSocket 返回 ready 后播报“已开始会议记录”；新绑定路径增加“绑定成功”。设备重启只恢复凭证，不自动录音。旧版二维码接口为兼容保留，网页默认使用数字验证码流程。
