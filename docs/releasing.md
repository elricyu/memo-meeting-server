# 发布

源码与预编译 JAR 分开管理；JAR、runtime、数据和本机发布副本不进入 Git。

1. 运行构建与集成测试，检查源码不含 Key、数据库、录音和个人信息。
2. 统一 `backend/pom.xml`、`frontend/package.json`、锁文件和 CHANGELOG 的版本；构建脚本中的固定 JAR 文件名也需同步。
3. 在独立环境验证首次启动、注册、云端配置、浏览器录音及眼镜 v1 协议兼容性。
4. 创建版本标签，GitHub Release 附上 JAR、部署说明和 SHA256 校验值，注明需要 Java 21。
5. 发布说明注明兼容的眼镜客户端版本和已知问题。

当前尚未创建 GitHub Release、发布标签或自动发布工作流。GitHub 自动生成的源码包不是可运行的 JAR。
