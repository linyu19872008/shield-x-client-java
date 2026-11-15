# Changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-11-15

### Added

- Maven multi-module build, Maven Wrapper, Spotless, JaCoCo and Enforcer.
- Core `ShieldXClient`: policies, templates, event reporting, ETag/304, JSON/NDJSON, gzip.
- Contract models with forward-compatible `JsonNode` values and null tri-state validation.
- Optional `Poller`, `Reporter` and `FileSpool` with bounded queue, backoff and replay.
- Spring Boot starter with properties, lifecycle, Health and Micrometer gauges.
- Optional `shield-x-template` reference renderer passing all 14 conformance vectors.
- Raw HTTP contract probe and self-contained probe CI.
- MIT license, security policy and contribution guide.

[0.1.0]: https://github.com/linyu19872008/shield-x-client-java/releases/tag/v0.1.0
