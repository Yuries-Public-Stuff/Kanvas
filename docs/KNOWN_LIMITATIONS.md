# Known Limitations

Kanvas is pre-release software. This page lists the main limitations users should expect today.

## Compose Desktop compatibility

Kanvas does not yet reproduce every Skia/Skiko operation used by every Compose Desktop application.

Common UI rendering is supported, but complex applications can still hit unsupported or partially supported drawing paths.

Use `kanvasCompatibility` to record the detected Compose/Skiko runtime versions, then enable strict mode when you specifically want unsupported primary-frame rendering to fail clearly. The compatibility task is a version-reporting aid, not a renderer certification.

## Text rendering

Kanvas currently has compatibility text rendering, but full native text shaping, font fallback, and glyph-atlas rendering are still in progress.

Expect differences with:

- complex scripts
- unusual font fallback
- advanced text effects
- exact glyph metrics
- some IME/composition behavior

## Images and effects

Some image/effect paths still use compatibility lowering instead of the final persistent GPU-resource path.

Broader support is still needed for:

- advanced blend modes
- mask filters
- path effects
- arbitrary color filters
- some layered/effect-heavy drawing

## Platform status

### macOS

Metal is the primary backend.

Apple Silicon is a target and the Metal path exists, but full repeatable hardware verification is still ongoing.

Intel macOS should be treated as less-tested until dedicated hardware verification is recorded.

### Linux

Vulkan is the primary backend.

Attached rendering currently depends on X11 or XWayland. Native Wayland integration is not complete. The current OpenGL frame implementation is Windows-only.

Driver behavior has not yet been validated across a broad matrix of Mesa, NVIDIA, and AMD systems.

### Windows

Vulkan, OpenGL, and Direct3D 9 paths exist.

Device-loss, long-running resource stability, resize/minimize/restore, and cleanup behavior still need broader real-hardware verification.

GDI is a software/reference path. It is not GPU acceleration.

## ARM64

ARM64 is a project target, not yet a blanket guarantee for every supported operating system.

Apple Silicon is the main ARM64 path today. Other ARM64 targets still need ABI and runtime verification.

## Packaging

Kanvas can integrate with Compose Desktop distributables, but packaging is still pre-release.

Always test the generated application on the operating system and architecture you intend to distribute.

## Automatic project detection

Kanvas attempts to detect desktop modules and useful Gradle tasks, but unusual repository layouts can require explicit configuration.

Example:

~~~kotlin
kanvas {
    target = ":desktop"
    buildTask = ":desktop:build"
    runTask = ":desktop:run"
}
~~~

## Accessibility and platform integration

Full accessibility parity is not complete.

Exact DPI behavior, IME behavior, native text behavior, and multi-window/dialog behavior are still being validated across platforms.

## Compatibility reports are not guarantees

`kanvasCompatibility` does not have a pass/fail renderer verdict. It reports detected Compose/Skiko runtime JARs and whether the current development baseline appears in their names. Real compatibility still requires application and hardware testing.

## Reporting a limitation

If you hit a problem that is not listed here, please open an issue.

When possible, include the affected repository. If the repository cannot be shared, provide a minimal project or another reproducible way for maintainers to exercise the same functionality.
