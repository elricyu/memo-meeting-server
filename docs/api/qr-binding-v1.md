# HTTPS 扫码绑定协议 v1

正常绑定不需要 USB、终端、人工解锁口令或语音报码。用户登录网页 → 我的眼镜 → 生成二维码；眼镜连接同一 HTTPS 服务，看向二维码并触发“绑定会议账号”；网页显示眼镜的 clientId/MAC，用户确认后才绑定。

## 网页

- `POST /api/v1/devices/web-bindings`：网页登录 + CSRF，生成有效期 5 分钟的申请，返回 `id,qrToken,expiresAt,status=WAITING,device=null`。qrToken 为 32 随机字节的64位小写hex，后端仅存 SHA-256。新申请取消本账号未完成的旧申请。
- 二维码由 Vue qrcode-generator 本地编码，内容为 JSON：`{"v":1,"requestId":"<申请UUID>","token":"<64位随机token>"}`。不含账号密码、云端Key或长期设备凭证，也不改变眼镜已配置的服务地址。
- `GET /api/v1/devices/web-bindings/{id}`：只允许发起用户读取，返回 `WAITING/CLAIMED/BOUND/EXPIRED/CANCELLED` 和申请硬件；此接口不返回 qrToken。网页每2秒查询。
- `POST /api/v1/devices/web-bindings/{id}/confirm`：只允许发起用户，在 `CLAIMED` 且未过期时核对硬件后确认。ownerId 从登录用户获取，客户端不能指定。
- `POST /api/v1/devices/web-bindings/{id}/cancel`：撤销未完成申请及临时设备凭证。旧会议不转移。

## 眼镜

参考工程锁定 SDK commit `a2c0c6b9af1d7164da38888d8ec4c3a437fff2a0` 的相机头文件；真机 SDK 已升级为同步抓拍协议，旧静态库会超时，设备动态 SDK 又有缺失的 TLS 链接依赖。当前轻量取图适配器按真机 SDK 使用 `AI_MEDIA_REQUEST → AI_MEDIA_CAPTURE|5000 → AI_MEDIA_RELEASE` 和相同 JPEG 共享内存 ABI。不另占 VI/VENC、不修改固件和参考工程、不写入用户相册。新增抓拍程序位于当前项目 glasses-client/device/capture-qr.c，约8KB。

- `POST /api/v1/devices/binding-claims/scan`：未绑定眼镜通过 HTTPS 提交 `{device:{clientId,mac,displayName,platform,deviceToken,clientRequestId},jpegBase64}`。JPEG≤2MiB，尺寸≤1200万像素。Spring Boot ZXing 解码二维码后按高熵token查找申请；不调用AI识别，也不保存图片或把二维码/凭证写入日志。单进程最多1个并行解码任务，按IP限速。
- 已自行完成本地解码的客户端也可 `POST /api/v1/devices/binding-claims`，提交 `{device,requestId,token}`。
- deviceToken 由眼镜生成32随机字节，后端只存hash；clientRequestId为UUID，断网重试保持一致；clientId来自稳定eMMC CID，MAC来自wlan0。
- 响应 `pairingId,deviceId,status=PENDING,expiresAt,pollIntervalSeconds=3`；不返回另一种传统绑定码。此时设备不能创建会议或访问任何用户数据。
- 使用既有 `GET /api/v1/devices/pairings/{pairingId}` + `Authorization: Bearer <deviceToken>` 查询。用户确认后为 `BOUND`，此后设备创建会议自动归入确认用户。
- `POST /api/v1/devices/binding-claims/recover`：凭证持有设备以 `clientRequestId,deviceToken` 恢复丢失响应中的pairingId，不返回用户密码或长期Key。

一次二维码最多接纳一副眼镜，确认后不可重放。过期和取消会撤销待确认设备，释放clientId/MAC。clientId或MAC已有有效绑定时不得抢占；需要原用户先在网页解绑。

## 凭证与重启

扫码客户端将设备凭证以既有AES-256-GCM格式保存到独立 `scan-credentials.enc`；设备本地随机密钥 `scan-key` 和密文均0600，父目录0700；重启自动恢复并向服务校验当前绑定，解绑后旧token立即失效。旧 `credentials.enc` 手动解锁库保留，不被覆盖。当前方案依赖设备文件权限，尚未声明具备硬件密钥隔离；物理设备被攻破时需在网页撤销绑定。

## 验收边界

自动测试覆盖 Vue QR→JPEG→ZXing→HTTPS协议、确认前禁止录音、跨用户拒绝确认、重放/过期/取消拒绝、clientId/MAC保存、设备重启凭证恢复。真机扫码距离、光照、摄像头对屏幕角度及物理语音触发仍需用户佩戴眼镜验收；不能用合成二维码代替此项。

2026-10-13 真机更新：已原子切换扫码客户端，保留 credentials.enc 与原二进制备份；语音函数增加 bind_meeting_account，保留会议开关和视频开关。正式取图组件连续两次抓拍成功（198537 / 198749 字节），测试照片立即删除。当前应用 idle，旧手动凭证保持 LOCKED，等待原用户在 HTTPS 网页解绑并完成一次真实扫码确认。此证据证明相机取图，不等同于已通过对屏扫码和语音命中验收。

## 打开应用前服务器检查与反馈（2026-10-13）

`POST /api/v1/devices/binding-claims/check`：原生客户端提交 `{clientId,mac,deviceToken?}`；返回仅含state和固定message，不返回owner、token、设备内部ID或用户数据，不授予认证。POST允许匿名设备，GET拒绝，按IP限30次/分钟。

状态：UNBOUND=当前硬件无有效绑定；PENDING=凭证匹配但等待网页确认；BOUND=clientId/MAC及token同时匹配有效绑定；REBIND_REQUIRED=硬件已有绑定或未过期申请，但凭证缺失/不匹配。LOCKED是本地旧库状态，不能据此断言服务器未绑定。

眼镜`open`先查服务器；UNBOUND/REBIND_REQUIRED进入扫码并给明确语音，PENDING提醒网页确认，BOUND继续录音。绑定流程仍要求发起网页用户确认，无自动绑定或抢占。确认后，只有之前明确发起的打开请求继续录音。固定阶段提示由SDK播报；扫码最多3分钟，未识别时定期提示调整距离，确认最多等待4分钟。
