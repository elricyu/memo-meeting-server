# 本地语音处理模块

Memo Meeting 的可选独立服务：Whisper large-v3 + 中文时间对齐 + Community-1 说话人分离。文件转录在自己部署的 GPU 上完成，不调用云端转录 API。主服务仅通过 HTTP 异步接口接入，不需要 Python 或 GPU。

## 部署

实测环境为 Ubuntu 22.04、12GB RTX 4070 Ti；单进程、batch size 1。首次启动需联网下载模型和依赖，约需 7.4GiB 峰值显存；并非所有显卡或驱动已验证。

1. 将本目录完整复制到 GPU 主机的独立目录。
2. 在 Hugging Face 接受 [Community-1 的访问条件](https://huggingface.co/pyannote/speaker-diarization-community-1)，将只读 Token 保存到 `~/.cache/huggingface/token`，权限600；不要提交到 Git。
3. 执行 `bash scripts/install.sh`。Ubuntu 22.04 缺少解码库时执行 `bash scripts/install-ffmpeg.sh`，只在本目录解包依赖。
4. 执行 `bash scripts/start.sh` 注册并启动用户服务。检查 `systemctl --user status memomeet-gpu-speech` 和 `runtime/service.log`。
5. `/health` 返回 `status: ready` 后才接收任务。需要无人登录时运行，由管理员执行 `sudo loginctl enable-linger <服务用户>`。

默认仅监听 GPU 主机 `127.0.0.1:18092`。主服务所在机器建立 SSH 隧道：

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -L 127.0.0.1:18192:127.0.0.1:18092 gpuserver
```

可用 systemd 管理这个隧道并设置失败重启。GPU 主机 SSH 用户名及主机地址放在部署者的 SSH 配置中，不写入源码。默认不开放公网。若部署者通过受控私网/HTTPS提供访问，可在服务目录的 `.env` 设置 `LOCAL_SPEECH_TOKEN`；所有请求包含 `Authorization: Bearer <token>`。Token 必须保密；没有 Token 时只允许 SSH 隧道等受控访问。

主服务管理员在“AI 服务设置 → 本地转录”填写可达地址，例如 `http://127.0.0.1:18192`，测试本地连接、启用并保存。自动排队默认勾选。主服务和 GPU 不需要部署在同一台机器。

## 接口契约 v1.1

输入：16kHz、单声道、PCM16 WAV，最多128MiB、1小时。上传暂存副本推理后删除；SQLite任务状态与结果保存在 `runtime/jobs`。录音原文件留在主服务。

```bash
curl http://127.0.0.1:18192/health
curl -X POST http://127.0.0.1:18192/v1/jobs \
  -F request_id=5d830336-8f7a-48dd-8fa3-1935a7f1c0af \
  -F file=@meeting.wav
curl http://127.0.0.1:18192/v1/jobs/5d830336-8f7a-48dd-8fa3-1935a7f1c0af
```

`POST /v1/jobs` 返回202：`{"jobId":"UUID","status":"queued","statusUrl":"/v1/jobs/UUID"}`。`request_id` 可选UUID；同一编号再次上传返回已有任务，不重复处理。失败后需要新的编号重试。正在上传时重复请求返回409，稍后查询同一编号。可选 `speaker_count`（1–20）约束人数，`hotwords`（最多2000字符）提供提示。

`GET /v1/jobs/{id}` 状态为 `queued`、`running`、`completed` 或 `failed`。完成时包含 `result`：

```json
{"status":"completed","result":{"text":"确认方案。","durationMs":1000,"segments":[{"startMs":0,"endMs":1000,"text":"确认方案。","speakerId":"SPEAKER_00"}],"speakers":["SPEAKER_00"]}}
```

未知说话人为 `null`，编号仅在单场录音内有效，不识别人名。`GET /v1/jobs/{id}/transcript` 导出TXT；`/docs` 提供服务自身的交互文档。

错误：401认证失败、404任务不存在、409同编号上传中、413上传过大、422输入无效、429队列满、503模型未就绪。不要在重试时随意换编号，先查询旧编号，避免重复处理。服务重启将当时未完成的任务标记失败，主服务会退避并用新编号重新提交。

## 限制与许可

实测8–11分钟录音约26–38秒，耗时不含模型初始化、排队和传输。存在短句漏识别、术语错误、繁简混用与未分配说话人的片段；必须回听核对。模型不会凭编号识别真实姓名。无语音返回空结果，主服务标记“未检出语音”，不自动调用付费云端。

本目录自有代码使用仓库 Apache-2.0 许可；模型、依赖保留各自许可及访问条件：[WhisperX](https://github.com/m-bain/whisperX)、[faster-whisper](https://github.com/SYSTRAN/faster-whisper)、[Community-1](https://huggingface.co/pyannote/speaker-diarization-community-1)（CC-BY-4.0）。模型权重和第三方依赖不随源码分发。安装解析版本保存在部署机器 `requirements.lock.txt`，模型修订固定在 `whisper_engine.py`。
