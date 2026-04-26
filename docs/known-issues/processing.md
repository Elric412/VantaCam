# Processing Known Issues

### I2026-04-26-1: AI downsampler returned black tiles
- Observed in: `AiEngineOrchestrator.downsample()`
- Severity: BLOCKER
- Owner: Lumo Imaging Engineer + kotlin-specialist
- Linked plan: `Plan.md#p22--aiengineorchestratordownsample-returns-zeros`
- Resolution: resolved by nearest-neighbour sampling from `FusedPhotonBuffer` photon planes.

### I2026-04-26-2: FusionLM2, ProXDR, neural AWB, and ToneLM2 were not on the shutter path
- Observed in: `CaptureProcessingOrchestrator.processCapture()`
- Severity: BLOCKER
- Owner: Lumo Imaging Engineer + android-app-builder
- Linked plan: `Plan.md#sub-plan-p4--runtime-wiring-shutter--processing`
- Resolution: resolved with explicit engine injection and staged calls with safe fallbacks.

### I2026-04-26-3: Real Camera2 forward matrix ingestion is still TODO
- Observed in: `CaptureProcessingOrchestratorExtensions.identitySensorToXyz3x3()`
- Severity: MAJOR
- Owner: color-science-engineer
- Linked plan: `processing-problems.md#4-hypertone-awb--neural-prior-never-reaches-the-wb-engine`
- Resolution: open; replace identity matrix with `CameraCharacteristics.SENSOR_FORWARD_MATRIX1` plumbing.

### I2026-04-26-4: MicroISP sensor-id gate is deferred
- Observed in: `processing-problems.md#9-microisp--runner-exists-sensor-id-gate-never-consulted`
- Severity: MAJOR
- Owner: Lumo Imaging Engineer
- Linked plan: `Plan.md#p45--microisp-runner-only-fires-on-ultra-wide--front`
- Resolution: open; capture path now logs the gate TODO.

### I2026-04-26-5: Color-science subordinate plan remains follow-up
- Observed in: prior color-science planning content
- Severity: MAJOR
- Owner: color-science-engineer
- Linked plan: `docs/color-science/Plan-CS.md`
- Resolution: open; can be executed now that build/DI remediation is in place.
