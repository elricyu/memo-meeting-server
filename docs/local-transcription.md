# 本地转录接入与任务管理

本地处理模块位于 [modules/local-speech](../modules/local-speech/README.md)，独立部署。浏览器和眼镜仍只连接 Memo Meeting，GPU服务仅由主服务访问。

管理员在 AI 服务设置中配置本地地址、可选 Bearer Token、启用开关和自动排队开关。Token 加密保存在数据库，不返回网页。测试只检查 `/health`，不提交音频。首次部署可设置主服务环境变量 `LOCAL_SPEECH_URL` 和可选 `LOCAL_SPEECH_TOKEN`，仅在尚无本地配置时初始化；之后以系统配置为准。

自动处理范围：已完成、有音频、属于账号且从未生成转录片段的会议，包括历史录音。存在已有原文或人工排除片段的会议不自动重写。录音中和中断但尚未结束的会议不会自动提交。默认串行处理，SQLite/H2两端持久化任务编号；主服务重启继续查询原任务，GPU服务重启导致失败时有限重试。网络或服务不可用时保留队列，退避最长5分钟；连续任务失败8次后显示失败，用户可重试。

云端转录保持用户手动触发，本地失败不会回退云端。本地任务完成后保存文字与说话人，不自动调用云端生成纪要。需要纪要时用户主动点击生成。云端手动转录或其他修改先产生原文时，本地结果不覆盖。

详情 `localTranscription` 提供 `status`、`error`：QUEUED排队、RUNNING处理中、RETRY等待重试、DONE完成、EMPTY未检出语音、FAILED失败、SKIPPED保留现有记录。队列处理不会改变录音本身的完成状态。网页在排队时保持状态更新，任务结束后关闭实时连接。

管理员接口（登录会话与CSRF）：`GET/POST /api/v1/admin/local-speech`，输入 `url`、`token`、`enabled`、`automatic`；空Token保留已有Token。`POST /api/v1/admin/local-speech/test` 检查待保存配置。会议所属用户可 `POST /api/v1/meetings/{id}/local-transcription` 重新排队未转录的已完成录音；设备身份不能修改配置或提交重试。

本地服务只支持最长1小时录音；超出上限会提示失败，部署者可在模块侧调整上限并同步主服务适配。原录音保留，语音识别和说话人编号可能不准确。备份时保留主服务数据库及 `ai-master.key`，模块任务数据只在服务机器保存，不进入 Git。
