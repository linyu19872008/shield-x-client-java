# 变更记录

[English](CHANGELOG.md) | **简体中文**

本文件记录项目的重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [0.1.0] - 2026-11-15

### 新增

- Maven 多模块、Maven Wrapper、Spotless、JaCoCo、Enforcer。
- 核心 `ShieldXClient`：策略/模板、事件上报、ETag/304、JSON/NDJSON、gzip。
- 契约模型：保留未知字段的 `JsonNode`、`matched_policy_id` 三态校验。
- 可选 `Poller`、`Reporter`、`FileSpool`：有界队列、退避、补发。
- Spring Boot Starter：配置、生命周期、Health、Micrometer。
- 可选 `shield-x-template`：14 条渲染向量全部通过。
- 裸 HTTP 契约探针与自包含 probe CI。
- MIT 许可证、安全策略、贡献指南。

[0.1.0]: https://github.com/linyu19872008/shield-x-client-java/releases/tag/v0.1.0
