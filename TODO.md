# Kanvas Roadmap

This file tracks the work that still matters for making Kanvas usable as a real Kotlin desktop rendering plugin.

A checked item means the implementation exists in the repository. It does **not** automatically mean the behavior has been verified on every operating system, GPU, or application.

## Current priorities

The project is currently focused on four things:

1. making normal Kotlin/Compose Desktop projects easy to run through Kanvas
2. improving rendering fidelity and compatibility
3. validating Metal, Vulkan, OpenGL, and Direct3D paths on real hardware
4. getting the Gradle plugin and packaging flow ready for public use

---

## 1. Kotlin project integration


- [x] Gradle plugin with build, run, runtime, compatibility, and packaging tasks
- [x] Support configurable Gradle targets instead of assuming every project uses `:desktop`
- [x] Zero-edit adapter for testing existing Kotlin repositories
- [x] Linux/macOS and Windows command-line wrappers
- [x] Compose/Skiko runtime-version report for troubleshooting
- [x] Runtime audit and capture output
- [x] Compose Desktop application/window interception
- [x] Package the Kanvas runtime into Compose Desktop distributables


- [ ] Test the plugin against a wider set of real Kotlin/Compose Desktop repositories
- [ ] Improve automatic task/module detection for unusual Gradle layouts
- [x] Make setup errors clearer and more actionable
- [ ] Verify packaging on Windows, Linux, and macOS with clean test applications
- [ ] Publish the Gradle plugin once the integration path is stable enough for public use

---

## 2. Compose rendering compatibility


- [x] Capture supported Skia/Skiko drawing from Compose Desktop
- [x] Renderer-neutral display-list model
- [x] Support common transforms, clips, shapes, images, layers, alpha blending, gradients, and basic effects
- [x] Compatibility text rendering path
- [x] Pointer, keyboard, focus, scrolling, and basic IME plumbing
- [x] Strict renderer mode for detecting unsupported primary-frame drawing


- [ ] Finish native text shaping, font fallback, and glyph atlas rendering
- [ ] Move remaining image paths to persistent GPU textures
- [ ] Expand blend mode, filter, mask, and path-effect coverage
- [ ] Improve multi-window and dialog behavior
- [ ] Improve DPI and input parity across platforms
- [ ] Add reliable screenshot/pixel comparison tests for supported UI scenes
- [ ] Validate accessibility and platform text/IME behavior before claiming full parity

---

## 3. Native GPU backends

### macOS

- [x] Native Cocoa window/input path
- [x] Metal renderer
- [x] Attached rendering through the existing Compose/AWT host
- [ ] Complete real-hardware validation on Apple Silicon
- [ ] Validate Intel macOS where hardware is available
- [ ] Add repeatable resize, minimize/restore, input, and screenshot tests

### Linux

- [x] X11 attached window path
- [x] Vulkan renderer path
- [ ] Improve Vulkan resource lifetime and synchronization
- [ ] Validate on multiple Mesa/NVIDIA/AMD driver combinations
- [ ] Improve Wayland support beyond the current XWayland/X11 path

### Windows

- [x] Win32 attached window path
- [x] Vulkan renderer path
- [x] OpenGL renderer path
- [x] Direct3D 9 renderer path
- [x] GDI software/reference renderer
- [ ] Validate device-loss, resize, minimize/restore, and cleanup behavior
- [ ] Verify long-running resource stability
- [ ] Revisit modern Direct3D backends only if they provide a clear project benefit

---

## 4. ARM64 and architecture support

- [x] macOS build path supports Apple Silicon targets
- [x] Native code builds without requiring Vulkan on macOS
- [ ] Audit native casts, buffer layouts, pointer assumptions, and ABI boundaries for ARM64
- [ ] Verify ARM64 runtime behavior on supported platforms
- [ ] Treat ARM64 as a release target only after real hardware testing

---

## 5. Testing and verification

- [x] Gradle plugin tests (verified on Gradle 9.8.0)
- [x] Java instrumentation-agent tests
- [x] Native C tests
- [x] Renderer/display-list tests
- [x] Doctor diagnostics and Compose/Skiko runtime reporting
- [x] Local release-check scripts
- [x] Benchmark and telemetry tooling for renderer development


- [ ] Establish a small set of real reference applications for compatibility testing
- [ ] Run repeatable end-to-end tests on Windows, macOS, and Linux
- [ ] Add screenshot/pixel-diff validation for representative UI scenes
- [ ] Add resize, minimize/restore, multi-window, and input regression tests
- [ ] Add long-running memory/resource stability checks
- [ ] Separate "builds successfully" from "verified on real hardware" in release notes and issue tracking

---

## 6. Real application compatibility

Wake remains a useful stress-test application, but Kanvas should not be designed around one project.


- [x] Initial Wake compatibility/parity tooling
- [x] Wake-inspired rendering/input fixtures
- [x] External-project adapter supports real Compose Desktop repositories


- [ ] Re-test Wake on current macOS, Windows, and Linux builds
- [ ] Add several smaller public Compose Desktop applications as compatibility targets
- [ ] Track failures by renderer feature instead of by application where possible
- [ ] Require reproducible test projects or repositories for compatibility bugs when practical

---

## 7. Documentation and public readiness

- [x] Beginner-focused README
- [x] Getting started guide
- [x] Configuration guide
- [x] Troubleshooting guide
- [x] Architecture overview
- [x] Issue and merge-request templates
- [x] AI/contributor guidance
- [ ] Add verified installation examples for each supported operating system
- [ ] Add screenshots or short demos from real applications
- [x] Document known limitations by backend
- [x] Add a compatibility/version baseline document
- [x] Add basic, multi-module, and plain Kotlin example projects
- [x] Add support, security, conduct, versioning, release, and dependency documentation
- [x] Add clean-clone/example verification scripts
- [x] Include project and third-party notices with packaged Kanvas runtimes
- [ ] Define the final minimum supported Kotlin, Compose Desktop, and Skiko versions for the first public release
- [ ] Prepare the first public plugin release once the main integration and rendering paths are verified

---

## Later / optional work

These are useful ideas, but they are not current blockers:

- modern Direct3D 11/12 backend
- D3D9Ex
- advanced 3D benchmark expansion
- additional model/asset benchmark workloads
- GPU timestamp and presentation telemetry integrations
- power/VRAM/per-thread profiling
- broader Wayland-native integration
- optional MoltenVK/Vulkan support on macOS

---

## Release criteria

A first public release should not require perfect Compose parity, but it should meet a reasonable baseline:

- Kanvas can be added to a normal Compose Desktop Gradle project without custom source changes
- `kanvasDoctor`, `kanvasCompatibility`, `kanvasBuild`, and `kanvasRun` work predictably
- Metal works on a verified Apple Silicon Mac
- Vulkan or another primary backend works on verified Linux and Windows systems
- common UI elements render correctly enough for real applications
- basic input, resize, window, text, and image behavior work
- packaging works for at least the main supported desktop targets
- known limitations are documented
- unsupported renderer behavior fails clearly in strict mode
- hardware verification is documented separately from source implementation

The detailed implementation history belongs in commits and archived development notes, not in this roadmap.
