# ADR-003: LUMO parallel pipeline using coroutineScope/async

## Status
Accepted

## Context
The imaging pipeline involves multiple specialist engines (colour, depth, face, and scene analysis) that need to process the fused frame. A sequential chain, where each engine runs one after the other, is too slow to meet the latency requirements for capturing images, especially under zero-shutter-lag and multi-frame processing conditions. 

## Decision
We will design the LUMO pipeline to run concurrently using Kotlin's structured concurrency (`coroutineScope` and `async/await`). Specifically, the colour, depth, face, and scene engines will be dispatched in parallel to process the `FusedPhotonBuffer`. The pipeline is structured as a directed acyclic graph (DAG), relying on Kotlin type safety (such as `WbCorrectedBuffer`) to orchestrate dependencies between stages, rather than explicit barriers. The sequential chain alternative was rejected for being too slow.

## Consequences
- **Positive:** Significantly reduced latency for the overall capture pipeline, leveraging modern multi-core processing.
- **Positive:** Safe parallelism guaranteed by Kotlin's structured concurrency and immutable data structures.
- **Negative:** Higher peak processing load and increased complexity in thermal and resource management (handled by the `RuntimeGovernor`).
