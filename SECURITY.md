# Security Policy

## Supported versions

`0.1.x` is the initial line. Security fixes are applied to the latest release.

## Reporting a vulnerability

Please do not open a public issue for security problems. Report privately to the
maintainer through GitHub Security Advisories, or by email if a contact is listed
on the repository profile. Include:

- affected version/commit
- reproduction steps or a minimal proof of concept
- impact (token leakage, event disclosure, denial of service, etc.)

We aim to acknowledge reports within 3 business days and to publish a fix or
mitigation as soon as practical.

## Scope

In scope: token handling, TLS verification, redirect behavior, spool file
permissions, event/log disclosure, and dependency vulnerabilities.

Out of scope: gateway policy evaluation, template rendering, and deployment
network configuration.
