# 安全策略

[English](SECURITY.md) | **简体中文**

## 支持版本

`0.1.x` 是当前版本线，安全修复会应用到最新 Release。

## 报告漏洞

请不要为安全问题开公开 issue。请使用本仓库的 GitHub Security Advisories
（Security → Report a vulnerability），或通过仓库资料中的联系方式联系维护者。请包含：

- 受影响的版本/commit
- 复现步骤或最小 PoC
- 影响（Token 泄漏、事件泄露、拒绝服务等）

我们会在 3 个工作日内确认，并尽快发布修复或缓解措施。

## 范围

范围内：Token 处理、TLS 校验、重定向、spool 文件权限、事件/日志泄露、依赖漏洞。

范围外：网关策略求值、模板渲染、部署网络配置。
