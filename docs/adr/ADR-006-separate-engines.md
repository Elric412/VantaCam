# ADR-006: Separate depth-engine and face-engine from ai-engine

## Status
Accepted

## Context
Previously, both depth estimation and face mesh/skin mapping functionalities were bundled within a single generic `ai-engine`. This combined approach created severe GPU and NPU scheduling contention, effectively bottlenecking the pipeline since depth sensing and face tracking needed to run sequentially or compete for AI inference resources, slowing down parallel execution.

## Decision
We will separate `depth-engine` and `face-engine` into independent modules, distinct from `ai-engine`. This allows independent scheduling, meaning that depth and face data can be processed concurrently or cached and reused independently by the White Balance, Bokeh, and Colour engines without being blocked by general scene classification tasks. The alternative of keeping them combined was rejected because it creates GPU scheduling contention.

## Consequences
- **Positive:** Enables true parallel execution as outlined in the LUMO parallel pipeline DAG.
- **Positive:** Better modularity and focused responsibilities for each engine.
- **Negative:** More modules to manage and slightly more complex inter-engine orchestration in `SmartImagingOrchestrator`.
