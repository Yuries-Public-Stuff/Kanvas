# Backend Status

This table separates **implementation exists** from **verified broadly on real hardware**.

| Backend | Platform | Implementation | Verification status | Notes |
| --- | --- | --- | --- | --- |
| Metal | macOS | Present | In progress | Primary macOS path |
| Vulkan | Windows | Present | In progress | Primary GPU path candidate |
| Vulkan | Linux | Present | In progress | Primary Linux path |
| OpenGL | Windows | Present | In progress | Alternative Windows path |
| Direct3D 9 | Windows | Present | In progress | Supported legacy Windows GPU backend |
| GDI | Windows | Present | Reference path | CPU/software reference, not GPU acceleration |

## What "Present" means

The backend has committed source and integration code.

It does **not** mean:

- every driver has been tested
- device loss and resize are fully proven
- output is pixel-identical to Compose/Skia
- long-running resource stability is proven
- every Compose operation is supported

## Automatic backend selection

Current `auto` behavior is:

- **macOS:** Metal only
- **Linux:** Vulkan only
- **Windows:** Vulkan → Direct3D 9 → OpenGL during backend creation; GDI remains a software/reference safety path

Runtime fallback on Windows intentionally stays on GPU backends and does not silently switch to GDI after rendering has started.

## macOS

Metal is the renderer that should be tested first.

Current work includes real-hardware verification, retained-frame behavior, resize/minimize handling, input, and cross-display scaling.

## Linux

Vulkan should be tested first.

Attached rendering currently depends on X11/XWayland. Native Wayland support is a later project. The current OpenGL frame implementation is Windows-only.

## Windows

Vulkan, OpenGL, and Direct3D 9 are all real GPU paths.

GDI is retained as a software/reference path for comparison and fallback development. It must not be described as GPU acceleration.

## Selecting a backend

Start with:

~~~kotlin
kanvas {
    backend = "auto"
}
~~~

Select a backend manually only when testing or when `auto` chooses a path that is known to be unsuitable for the current machine.
