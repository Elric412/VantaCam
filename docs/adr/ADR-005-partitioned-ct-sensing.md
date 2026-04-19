# ADR-005: Partitioned 4x4 CT sensing as default WB strategy

## Status
Accepted

## Context
Traditional white balance algorithms apply a single global Color Correction Matrix (CCM) per frame based on an averaged illuminant estimate. This approach, even when augmented with AI correction, routinely fails in mixed lighting scenarios (e.g., indoor lighting combined with window light), resulting in incorrect colour rendering for parts of the image.

## Decision
We will adopt a Partitioned 4x4 Colour Temperature (CT) sensing approach as the default WB strategy (HyperTone WB 2.0). The frame will be divided into a 16-tile grid, and a specific CCM will be independently estimated and applied for each tile based on hardware True Colour Camera readings and spectral dictionaries. We will never apply a single global CCM per frame. The alternative of a global CCM with AI correction was explicitly rejected as insufficient for mixed light scenarios.

## Consequences
- **Positive:** Accurate white balance across the entire frame, even in highly complex mixed lighting.
- **Positive:** Works perfectly in tandem with the `SkinZoneWbGuard` to prevent skin tone drifts.
- **Negative:** Increased computational cost to calculate and smoothly blend 16 distinct CCMs across tile boundaries.
