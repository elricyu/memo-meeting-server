# 开发与测试

依赖版本见 `backend/pom.xml`、`frontend/package-lock.json`。仓库包含后端和网页，眼镜客户端单独维护；不依赖相邻目录存在。

```bash
bash scripts/build.sh
bash scripts/test-integration.sh
```

构建脚本运行前端测试、类型检查、构建和 Java 测试。集成测试需要 Java 21、Node.js、curl，使用独立端口和临时数据库以及明确的 AI 协议模拟服务，不调用付费模型。端口可通过 `TEST_PORT`、`MOCK_PORT` 调整。

仅运行单元测试：

```bash
(cd frontend && npm ci && npm test)
(cd backend && mvn test)
```

`backend/src/main/resources/static/` 是生成目录，由构建脚本从前端和 API 文档复制，不提交 Git。修改前端请编辑 `frontend/src/` 或 `frontend/public/`。

协议变更需同步 OpenAPI、协议说明和配套眼镜兼容性。测试桩不能作为真实识别准确率的证据。不要对实际用户数据或生产录音执行测试。

`docs/api/openapi.json` 直接维护并提交 Git；构建时检查 JSON 格式，不再依赖 Python 生成。接口变更需同步修改此文件。
