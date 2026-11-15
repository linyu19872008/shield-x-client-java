# 贡献指南

[English](CONTRIBUTING.md) | **简体中文**

感谢参与 `shield-x-client-java`。

## 开发

要求 JDK 17+、Maven 3.9+。

```bash
./mvnw -B verify
```

构建包含 Spotless 格式检查。自动格式化：

```bash
./mvnw spotless:apply
```

## 契约变更

本模块实现 `agent-v1`。涉及线格式字段、错误语义、重试行为、资源限制或 ETag 处理的变更，
必须先对照 shield-x 契约讨论；夹具与 contract probe 要在同一个 PR 中更新。

## Pull Request

- 改动聚焦，不把重构和行为变更混在一起。
- 为新行为和失败路径补测试。
- 用户可见变更更新 `CHANGELOG.zh-CN.md`。
- 不提交 Token、私钥、租户数据或 `.codegraph/`。
- 文档变更同时检查中英文链接。

## 报告问题

请使用 Bug 模板，附上 SDK 版本、Java/Maven 版本、契约版本、预期行为和最小复现。
