# Changelog

This file tracks notable user-facing Kanvas changes.

Kanvas follows [Semantic Versioning](https://semver.org/).

## Unreleased

## 0.1.0 - 2026-10-06

### Added

- Gradle plugin `org.yurie.kanvas`
- native runtime build and integration tasks
- Compose Desktop rendering takeover path
- Metal, Vulkan, OpenGL, Direct3D 9, and GDI/reference backend paths
- compatibility and doctor tasks
- runtime audit/capture output
- zero-edit external-project adapter
- Compose Desktop packaging integration
- beginner documentation, troubleshooting, known limitations, and examples
- security, conduct, support, contribution, versioning, and release guidance

### Changed

- Gradle plugin test suite verified successfully on Gradle 9.8.0
- `kanvasDoctor` now performs blocking environment, toolchain, backend, task, source/runtime, and Compose/Skiko diagnostics with actionable fixes
- root-project targets such as `target = ":"` now resolve normal root Gradle task paths
- `kanvasCompatibility` now explicitly reports detected runtime versions instead of implying a renderer certification
- backend documentation now matches the implemented platform paths

- public-facing project name standardized on **Kanvas**
- Gradle plugin source and VCS metadata now point to the public Kanvas repository
- plugin usage documentation now correctly applies Kanvas to the root Gradle project and selects the app with `kanvas.target`

### Known limitations

See [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).
