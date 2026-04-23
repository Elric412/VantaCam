## Disabled Tests
These tests were disabled because they rely on `scripts/` or `source/` which are missing in this checkout. They need re-wiring after `scripts/` and `source/` restoration.

## Detector fixtures (C3) — deferred
The rule-level drift in `linked-stylesheet`, `color`, `modern-color-borders`,
`icon-tile-stack`, `layout`, `motion` is tracked separately. Do NOT modify
the detector here; fix in a rule-isolated PR later.