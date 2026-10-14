# 部署与备份

## 单进程运行

按 README 构建后，Vue 静态资源和后端打入 `backend/target/memo-meeting-0.1.0.jar`。使用 `scripts/start.sh` 加载本地 `.env` 并运行。端口默认 8090；配置参考 `.env.example`。

生产环境设置实际 `ALLOWED_ORIGINS`，启用 HTTPS，并设置 `SESSION_COOKIE_SECURE=true`。反向代理覆盖网页、REST、SSE、音频下载和 WebSocket，保留 Cookie 和 Authorization，关闭 SSE 缓冲，支持 WebSocket Upgrade。只在需要时设置 `BIND_ADDRESS=0.0.0.0`；默认仅监听本机。

Docker：复制并编辑 `.env` 后执行 `docker compose up --build -d`。默认映射本机 8090，持久数据保存在 `meeting-data` 卷；不要使用 `docker compose down -v` 清理需要保留的数据。

## 管理员与 AI 配置

先注册账号，停止服务后以 `ADMIN_USERNAME=已注册用户名 bash scripts/start.sh` 启动一次，将指定账号提升为管理员。授权成功后停止服务并正常启动，不继续保留该启动参数。Compose 部署时可在本地 `.env` 临时设置 `ADMIN_USERNAME` 并重建容器，授权完成后移除参数并再次重建容器。重新登录后打开「AI 服务设置」，测试并保存实际 ASR/LLM 配置。配置可包含真实付费请求；Key 仅保存在服务端。环境变量提供未保存全局配置时的默认值。

## 备份与恢复

停止服务后备份完整 `DATA_DIR`，包含数据库、录音和 `ai-master.key`。数据库中的 AI Key 经过加密，丢失主密钥将无法解密已有配置。备份属于敏感数据，不提交到 Git。

恢复到同一数据目录并保留权限后启动；不要让两个进程同时打开同一 H2 数据库。升级前备份数据和旧 JAR，数据库结构发生变更时回退可能需要同时恢复匹配的数据备份。

## 服务管理与录音存储

自行部署可以由 systemd 或其他进程管理器运行 `scripts/start.sh`；按实际部署目录、环境变量和数据权限设置服务。当前线上实例专用的运维脚本与内部记录不属于公开仓库。

WAV 使用 16kHz 单声道 PCM16，约 115 MB/小时。默认最长录音 4 小时，可调整 `MAX_RECORDING_SECONDS`。根据使用量监控磁盘空间并制定数据保留策略。

`/api/v1/health` 仅代表本地服务正常，实际 AI 配置应在管理员页面执行连接测试，并以真实录音验收。反向代理应关闭 SSE 缓冲、支持 WebSocket Upgrade，并设置足够的连接超时。

可选的 `scripts/prepare-release.sh` 用于 Linux 主机校验并生成不可变 JAR 副本，需要 Bash、unzip、sha256sum 和支持 `sync -f` 的 coreutils。它不启动或重启服务。源码构建与服务运行不需要 Python。
