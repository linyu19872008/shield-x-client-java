# Contributing

**English** | [简体中文](CONTRIBUTING.zh-CN.md)

## Development

Requirements: JDK 17+ and Maven 3.9+.

```bash
./mvnw -B verify
```

The build runs formatting checks with Spotless. Apply formatting with:

```bash
./mvnw spotless:apply
```

## Contract changes

`shield-x-client-java` implements `agent-v1`. Changes to wire fields, error
semantics, retry behavior or resource limits must be discussed against the
shield-x contract first. Add or update fixtures and the contract probe together.

## Pull requests

- Keep changes focused; avoid mixing refactors with behavior changes.
- Add tests for new behavior and failure paths.
- Update `CHANGELOG.md` for user-visible changes.
- Do not commit tokens, private keys, tenant data, or `.codegraph/`.
