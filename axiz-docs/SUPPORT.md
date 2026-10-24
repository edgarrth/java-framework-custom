# Support

## Supported versions

| Version | Status | Security fixes | Bug fixes |
|---|---|---|---|
| 0.1.x | current (pre-1.0, APIs may still change between minors) | yes | yes |

Policy once 1.0.0 is released:

- The latest minor receives bug and security fixes.
- The previous minor receives security fixes for six months after the next minor is released.
- A major version is supported for twelve months after the next major is released.
- End of support is announced in the release notes and in the documentation site at least three
  months in advance. Renovate proposes the upgrades and `axiz upgrade` applies the migration
  recipes.

The supported runtime is the one declared by the release: Java 25 and the Spring Boot line of the
BOM. Other combinations are not supported.

## Getting help

- Questions and usage: the documentation site first (`axiz-docs`), then the platform team channel.
- Bugs and feature requests: an issue in this repository with version, steps and expected
  behavior.
- Security issues: never in an issue; follow `SECURITY.md`.

