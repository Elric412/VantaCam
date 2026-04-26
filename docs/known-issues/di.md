# DI Known Issues

### I2026-04-26-1: LeicaLogger had no Android binding
- Observed in: multiple `@Inject` constructors importing `com.leica.cam.common.logging.LeicaLogger`
- Severity: BLOCKER
- Owner: Backend Enhancer
- Linked plan: `Plan.md#p11--add-androidleicalogger--loggingmodule`
- Resolution: resolved with JVM `NoOpLeicaLogger`, app `AndroidLeicaLogger`, and `LoggingModule`.

### I2026-04-26-2: Platform contracts had no default TrueColour sensor
- Observed in: `HypertoneWbModule.providePartitionedCTSensor`
- Severity: BLOCKER
- Owner: Backend Enhancer
- Linked plan: `Plan.md#p13--provide-truecolourhardwaresensor`
- Resolution: resolved with neutral `HardwareContractsModule` provider; vendor implementation remains future work.

### I2026-04-26-3: WB and neural ISP interfaces lacked graph bindings
- Observed in: `IHyperToneWB2Engine`, `INeuralIspOrchestrator`
- Severity: BLOCKER
- Owner: Backend Enhancer + kotlin-specialist
- Linked plan: `Plan.md#sub-plan-p1--di-graph-unsatisfied-bindings`
- Resolution: resolved with `HyperToneWB2EngineAdapter` and `NeuralIspOrchestratorImpl` providers.

### I2026-04-26-4: API constructor visibility widened for cross-module adapters
- Observed in: `HyperToneWBContracts.kt`, `ColorScienceContracts.kt`, `NeuralIspContracts.kt`
- Severity: MAJOR
- Owner: kotlin-specialist
- Linked plan: `Plan.md#p23--internal-constructors-blocking-the-adapter`
- Resolution: resolved in P2; public constructors are now deliberate API surface.

### I2026-04-26-5: LiteRtSession reflection needs a long-term typed accessor
- Observed in: `processing-problems.md#11-neural-awb-model--loaded-and-warmed-then-ignored`
- Severity: MINOR
- Owner: Backend Enhancer
- Linked plan: `Plan.md#appendix-a--complete-inventory-of-observed-issues`
- Resolution: in-progress; ProGuard keep rule added, typed accessor remains open.
