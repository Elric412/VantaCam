# SDK / Permissions Known Issues

### I2026-04-26-1: Notification permission missing for planned long captures
- Observed in: `app/src/main/AndroidManifest.xml`
- Severity: MAJOR
- Owner: android-app-builder
- Linked plan: `Plan.md#p33--missing-runtime-permission-plumbing-for-android-13-notifications`
- Resolution: resolved by declaring `POST_NOTIFICATIONS`.

### I2026-04-26-2: Portrait-only orientation is accepted but tracked
- Observed in: `app/src/main/AndroidManifest.xml`
- Severity: MINOR
- Owner: android-app-builder
- Linked plan: `Plan.md#p34--androidscreenorientationportrait-blocks-landscape`
- Resolution: deferred; portrait lock remains intentional for this camera UI.

### I2026-04-26-3: Scoped-storage target verified after SDK pin
- Observed in: `build-logic/convention/src/main/kotlin/LeicaConventionSupport.kt`
- Severity: MINOR
- Owner: android-app-builder
- Linked plan: `Plan.md#p35--manifest-meta-androidrequestlegacyexternalstorage`
- Resolution: resolved; targetSdk 34 keeps scoped storage enforced with no legacy flag.
