# ADR-004: Vulkan compute for LUT and WB shaders

## Status
Accepted

## Context
Processing 16-bit linear light data requires robust, high-performance GPU execution. Historically, OpenGL ES was used for such tasks. However, relying on OpenGL ES has resulted in fp16 precision issues when computing 3D LUTs and White Balance shaders, which degrades the final image quality and compromises our uncompressed 16-bit path principle.

## Decision
We will replace OpenGL ES with Vulkan for GPU compute tasks, particularly for LUT and WB shaders, to guarantee the required fp16 precision. Vulkan provides more deterministic execution and better hardware control for intensive compute workloads. The alternative to keep using OpenGL ES was rejected because the fp16 LUT precision provided was insufficient for our quality standards.

## Consequences
- **Positive:** Improved image quality due to adequate fp16 precision for tetrahedral LUT interpolation and per-tile CCM application.
- **Positive:** More predictable performance and lower CPU overhead on supported devices.
- **Negative:** Increased complexity in the rendering backend, requiring careful management of Vulkan contexts and fallback strategies for older devices.
