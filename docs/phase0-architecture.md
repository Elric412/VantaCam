# Phase 0 Architecture Baseline

## Module Graph

```text
:app
  └─ :feature:camera, :feature:gallery, :feature:settings

:feature:camera
  └─ :camera-core, :imaging-pipeline, :color-science,
     :hypertone-wb, :ai-engine, :neural-isp, :ui-components

:feature:gallery
  └─ :imaging-pipeline, :color-science, :ui-components

:feature:settings
  └─ :camera-core, :ui-components

:camera-core
  └─ :sensor-hal, :lens-model, :common

:imaging-pipeline
  └─ :camera-core, :gpu-compute, :common

:color-science
  └─ :gpu-compute, :common

:hypertone-wb
  └─ :color-science, :ai-engine, :common

:ai-engine
  └─ :gpu-compute, :common

:neural-isp
  └─ :camera-core, :gpu-compute, :ai-engine, :common

:sensor-hal
  └─ :common

:lens-model
  └─ :common

:gpu-compute
  └─ no module dependencies

:ui-components
  └─ :common

:common
  └─ no module dependencies
```

## Phase 0 Deliverables Included

- Multi-module Gradle skeleton with all requested modules.
- Hilt wiring and module-scoped dependency entry points for each module.
- `@CameraSessionScope` annotation in `:common`.
- `:common` baseline utilities (`LeicaResult`, `Logger`, `Constants`).
- `:gpu-compute` backend initialization skeleton with CPU fallback.
- CI baseline workflow for compilation, static checks, tests, and coverage upload.

## Build Variant Baseline

`app` includes `debug`, `staging`, and `release` build types and dev/prod flavors.
- `debug`: strict mode and LeakCanary flags enabled.
- `staging`: minified, logging/strict mode disabled.
- `release`: minified with release proguard rules and `arm64-v8a` ABI filter.

## Notes

- This phase intentionally contains infrastructure-only code and no camera feature logic.
- Subsequent phases should incrementally fill module internals while preserving this dependency graph.
