# Leica Cam

Production-grade, modular Android computational photography stack implemented in Kotlin.

## Architecture

This repository is a multi-module Gradle project designed around a deterministic, physics-first imaging pipeline:

- `app`: application entrypoint and integration wiring.
- `feature:*`: user-facing flows (camera, gallery, settings).
- `sensor-hal`: Camera2/session abstractions and metering/focus primitives.
- `camera-core`: shared camera domain contracts.
- `native-imaging-core`: NDK/JNI imaging runtime with lock-bounded queues and native ownership of pixel handles.
- `imaging-pipeline`: capture-to-output orchestration (HDR, denoise, sharpening, modes, video, output metadata).
- `color-science`: color transforms, LUTs, perceptual profile engines.
- `hypertone-wb`: illuminant estimation and white-balance stabilization.
- `ai-engine`: scene, face, depth, segmentation, and shot-quality inference orchestration.
- `neural-isp`: learned ISP routing and staged neural processing.
- `lens-model`: geometric and optical correction primitives.
- `gpu-compute`: GPU backend abstraction and compute initialization.
- `ui-components`: reusable camera/gallery UI models and components.
- `common`: shared result types, constants, scope annotations, logging.

## Current Implementation Status

Phases completed in codebase:

- ✅ Phase 0: foundation and module graph
- ✅ Phase 1: sensor/session infrastructure
- ✅ Phase 2: core imaging pipeline
- ✅ Phase 3: color science pipeline
- ✅ Phase 4: HyperTone white balance
- ✅ Phase 5: AI engine integration
- ✅ Phase 6: neural ISP stages and routing
- ✅ Phase 7: computational photography modes
- ✅ Phase 8: video pipeline primitives
- ✅ Phase 9: UI orchestration and gallery metadata experience
- ✅ Phase 10: output and metadata engines
  - DNG metadata composition with required tag groups/opcodes
  - HEIC profile selection (Display P3 + HDR10 profiles)
  - Extended `pc:` XMP metadata payload generation
  - Privacy-first metadata policy and bounded audit logging

## Build

```bash
./gradlew assemble
```

## Test

```bash
./gradlew test
```

Or run module-specific checks:

```bash
./gradlew :imaging-pipeline:test
```

## Notes

- Code is Kotlin-first with deterministic outputs for testability.
- Domain engines are kept platform-light where possible to maximize unit test coverage.
- Privacy defaults are conservative: location metadata is opt-in.
