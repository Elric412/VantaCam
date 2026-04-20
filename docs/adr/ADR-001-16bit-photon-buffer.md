# ADR-001: Adopt sealed PhotonBuffer as the exclusive 16-bit data carrier

## Status
Accepted

## Context
In order to maintain the highest quality of image data throughout the Leica Cam pipeline and avoid "AI painting" artifacts, the data carrier must support an uncompressed 16-bit path. Previously, different pipeline stages might have used varying formats, leading to intermediate steps truncating data to 8-bit or using raw byte arrays. Raw byte arrays are error-prone, untyped, and can lead to silent precision loss across module boundaries.

## Decision
We will adopt the sealed `PhotonBuffer` and its variants (such as `FusedPhotonBuffer`) as the exclusive 16-bit data carrier across all imaging pipeline stages. All inter-module communication of image data will pass these sealed immutable value types. We rejected the alternative of using raw byte arrays or primitive buffers because they lack type safety and do not strictly enforce a 16-bit-per-channel linear light data requirement.

## Consequences
- **Positive:** Type-safe enforcement of the uncompressed 16-bit path. Intermediate 8-bit truncation is impossible at compile time.
- **Positive:** Sealed hierarchy allows compile-time checks, and immutable types prevent shared mutable state bugs.
- **Negative:** Increased memory overhead compared to 8-bit buffers and requires careful lifecycle management (e.g., C++ RAII handling).
