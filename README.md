# Memo Meeting Server

开源会议记录服务，包含 Spring Boot 后端和 Vue 网页端。支持浏览器录音，以及配套 RV1106B 开源眼镜应用录音；同一账号可在网页实时查看转录、回听音频、生成和导出会议纪要。

眼镜应用在独立的 [memo-meeting-glasses-client](https://github.com/elricyu/memo-meeting-glasses-client) 仓库维护，不包含在本仓库中。两个仓库通过 [v1 协议](docs/api/protocol-v1.md) 通信。

## 在线使用与自部署

已部署的线上实例：[https://memo-meeting.easymr.cn](https://memo-meeting.easymr.cn)。眼镜客户端默认连接此实例：安装眼镜应用、在网站注册账号并扫码绑定眼镜后，即可通过语音开启会议记录，无需自行部署服务端或配置 AI Key。

也可以自行部署本仓库，配置自己的 AI 服务，并将眼镜的 `MEMO_SERVER` 改为自己的 HTTPS 服务地址。自部署步骤见下方快速开始和 [部署说明](docs/deployment.md)。

## 关联项目

- [memo-meeting-server](https://github.com/elricyu/memo-meeting-server)（本仓库）：后端与 Web。
- [memo-meeting-glasses-client](https://github.com/elricyu/memo-meeting-glasses-client)：眼镜会议记录应用。

配套 [memo-meeting-glasses-client](https://github.com/elricyu/memo-meeting-glasses-client) 运行在 [OpenSource-Ai-Glasses](https://github.com/Iam5tillLearning/OpenSource-Ai-Glasses) 项目的 RV1106B 开源眼镜上。眼镜平台提供固件和 SDK，客户端负责采集与上传音频，本仓库提供后端与网页服务。

浏览器录音可独立使用本服务；眼镜录音需要安装配套客户端。

## 功能与边界

- 注册登录、用户数据隔离、扫码绑定眼镜和解绑。
- 流式转录、草稿与确认句、时间戳回听、WAV 保存。
- 浏览器断线续录、完整录音重新转录、AI 纪要与 Markdown 导出。
- 管理员配置和测试统一云端 AI 服务；普通用户不需要持有 API Key。

语音识别和纪要调用外部 AI 服务，不内置本地模型。自行部署时需要提供服务账号和 API Key，相关费用由服务部署者承担。眼镜断网自动补传不在当前客户端能力范围内；自动测试不代表真实会议识别质量。

## 快速开始

构建需要 Java 21、Maven 3.9+、Node.js 22、npm，以及 Bash；运行构建后的 JAR 只需要 Java 21。

在仓库根目录执行：

```bash
cp .env.example .env
# 编辑 .env 配置云端服务；不要提交真实 Key
bash scripts/build.sh
bash scripts/start.sh
```

访问 <http://127.0.0.1:8090>，注册账号。数据库、加密主密钥及录音默认位于 `data/`。没有可用 AI 配置时录音会明确失败，不会自动使用模拟服务。

也可执行 `docker compose up --build -d`；Compose 使用 `.env` 和持久数据卷。生产部署必须配置 HTTPS，参见 [部署说明](docs/deployment.md)。

## 使用

浏览器录音：登录 → 新建会议 → 开始录音 → 停止并等待保存 → 查看转录、回听或生成纪要。浏览器麦克风要求 HTTPS 或 localhost。

眼镜录音：安装独立眼镜应用 → 登录网页「我的眼镜」生成绑定二维码 → 眼镜说“绑定会议账号”并看向二维码 → 网页核对设备并确认 → 说“打开会议记录 / 关闭会议记录”。仅看向二维码不会触发扫码。解绑撤销设备凭证，历史会议仍属于原账号。

## 开发与文档

```bash
# 分别在两个目录运行开发服务
(cd backend && mvn spring-boot:run)
(cd frontend && npm ci && npm run dev)
```

前端开发地址为 <http://127.0.0.1:5173>，代理后端 8090 端口。

- [用户指南](docs/user-guide.md)
- [部署与备份](docs/deployment.md)
- [开发与测试](docs/development.md)
- [发布说明](docs/releasing.md)
- [API 与实时音频协议](docs/api/protocol-v1.md)、[OpenAPI](docs/api/openapi.json)
- [扫码绑定协议](docs/api/qr-binding-v1.md)
- [贡献指南](CONTRIBUTING.md)、[安全说明](SECURITY.md)

## 许可

本项目原创代码采用 [Apache License 2.0](LICENSE)。第三方组件保留各自许可，见 [第三方说明](THIRD_PARTY_NOTICES.md)。开源许可证不包含云端服务额度或托管服务承诺。
