# Wiring / Architecture Known Issues

### I2026-04-26-1: Capture orchestrator depends on engine impl modules
- Observed in: `core/capture-orchestrator/build.gradle.kts`
- Severity: MAJOR
- Owner: architecture + Lumo Imaging Engineer
- Linked plan: `Plan.md#p41--captureprocessingorchestratorfusionframes-is-a-stub`
- Resolution: resolved for Phase 0 via documented exception; long-term fix is an imaging-pipeline facade API.

### I2026-04-26-2: ZSL ring buffer still needs a real frame feed
- Observed in: `processing-problems.md#12-zsl-ring-buffer--nothing-ever-adds-a-frame`
- Severity: BLOCKER
- Owner: android-app-builder
- Linked plan: `processing-problems.md#12-zsl-ring-buffer--nothing-ever-adds-a-frame`
- Resolution: open; requires CameraX ImageAnalysis -> capture ingestor wiring.

### I2026-04-26-3: Native preview runtime is not fed preview frames
- Observed in: `processing-problems.md#13-native-vulkanc-runtime--never-fed-a-preview-frame`
- Severity: MAJOR
- Owner: android-app-builder + devops-infrastructure
- Linked plan: `processing-problems.md#13-native-vulkanc-runtime--never-fed-a-preview-frame`
- Resolution: open; Surface/HardwareBuffer forwarding remains a separate change.
