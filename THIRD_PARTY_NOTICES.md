# 第三方组件

Apache-2.0 适用于本项目原创代码，不改变第三方组件的许可证。依赖精确版本以 Maven、npm 锁文件和构建解析结果为准。

主要运行依赖：Spring Boot / Spring Framework / Spring Security、ZXing（Apache-2.0）；H2（MPL-2.0 / EPL-1.0）；Vue、markdown-it、qrcode-generator（MIT）；Lucide 图标（ISC）。开发依赖包括 Vite、TypeScript、vue-tsc、ws 等，各自适用原许可证。

完整依赖包含传递依赖。发布二进制或容器时应从实际解析的 Maven/npm 依赖收集许可文本及适用 NOTICE，随发布产物提供；本文件不是完整的依赖 SBOM 或许可文本合集。Java、Node、容器基础镜像适用各自许可。

Scriberr 仅作为资源和流程设计参考，不是本仓库运行依赖，未将其工程打包进本项目。
