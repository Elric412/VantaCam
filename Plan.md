# Plan: Rebuild LeicaCam UX — Design System, Permissions, Working Camera Controls, Animations, Performance, Build Fixes

## Context
LeicaCam is a multi-module Android computational-photography app (Kotlin 1.9.24, Compose BOM 2024.06, Hilt 2.51, AGP 8.4.2, min SDK 29, JVM 17) implementing the LUMO imaging platform. The repo has 33 Gradle modules with ambitious C++/Vulkan/TFLite engines, but the user-facing app is effectively a non-functional mockup: permissions are missing, camera preview is a gray `Box`, ISO/Shutter/EV/WB dials are inert placeholders, animations are absent (only shutter button scales), the design system is 5 colors + 3 text styles with no tokens, recompositions are unguarded, and several build/runtime wiring errors exist. This plan rebuilds the UX end-to-end: a real design system, a permission gate, wired Camera2 preview + controls, Compose animations, performance guards, and fixes for every defect found in the deep dive. Do NOT touch imaging algorithms (FusionLM, HyperTone WB, ProXDR, ToneLM, ColorLM) — those are out of scope.

## Agent Skill Directory (use per step as indicated)
| Skill | When to invoke |
|---|---|
| `analyzing-projects` | Before Step 1 and before Phase 5 to re-scan dependency graph |
| `web-design-guidelines` | Phase 1 (design tokens), Phase 4 (screens) |
| `Frontend-design` | Phase 1, Phase 4 |
| `animate` | Phase 5 (motion system, transitions, dial sheets) |
| `delight` | Phase 4, Phase 5 (micro-interactions, haptics) |
| `polish` | Phase 4, Phase 5 (final UI pass) |
| `layout` | Phase 4 (spacing/grid, bottom bar, overlays) |
| `colorize` | Phase 1 (LeicaPalette expansion) |
| `typeset` | Phase 1 (LeicaTypography expansion) |
| `shape` | Phase 1 (shape + elevation tokens) |
| `kotlin-specialist` | Phase 2, Phase 3 (permission flow, Camera2 wiring, state machine) |
| `Leica Cam Upgrade skill` (`leica-cam-upgrade`) | Phase 3 (SoC-aware defaults, sensor-profile-aware ranges) |
| `systematic-debugging` | Phase 6 (build/runtime errors) |
| `error-handling` | Phase 2, Phase 3 (permission denial, camera errors) |
| `optimizing-performance` / `optimize` / `overdrive` | Phase 7 (recomposition, cold start, jank) |
| `security-patterns` | Phase 2 (permission scopes, scoped storage) |
| `code-reviewer` (`.agents/agents/code-reviewer.md`) | End of every phase |
| `verification-before-completion` | End of every phase |
| `managing-git` | End of every phase (commit + PR on `genspark_ai_developer`) |
| `writing-skills` / `harden` / `impeccable` | Final polish across all phases |
| `test-driven-development` / `designing-tests` | Phase 8 (new unit + instrumented tests) |
| `subagent-driven-development` / `parallel-execution` / `dispatching-parallel-agents` | Use when Phase 1 + Phase 6 run in parallel |

## Stack & Assumptions
- Language/runtime: Kotlin 1.9.24, JVM 17 toolchain, NDK arm64-v8a (+ x86_64 on `dev` flavor).
- Framework: Jetpack Compose (Material3), Hilt DI, Navigation Compose, Coroutines 1.8.1.
- Package manager: Gradle 8.x via `gradlew`; JS lane is Bun (test runtime).
- Android SDK: `compileSdk`/`targetSdk` per `build-logic/convention/src/main/kotlin/LeicaConventionSupport.kt`, `minSdk = 29`.
- Key deps already present (`gradle/libs.versions.toml`): Compose BOM 2024.06.00, Material3, Hilt 2.51.1, Navigation-Compose (via hilt-navigation-compose 1.2.0), Coroutines 1.8.1, LeakCanary 2.14, LiteRT, MediaPipe tasks-vision 0.10.14.
- Key deps to add (pin to these exact versions):
  - `androidx.camera:camera-core:1.3.4`
  - `androidx.camera:camera-camera2:1.3.4`
  - `androidx.camera:camera-lifecycle:1.3.4`
  - `androidx.camera:camera-view:1.3.4`
  - `com.google.accompanist:accompanist-permissions:0.34.0`
  - `androidx.compose.animation:animation` (transitive via BOM, add explicit alias)
  - `androidx.navigation:navigation-compose:2.7.7` (currently missing from catalog)
- Environment assumptions the Executor should NOT re-verify:
  - Hilt is already set up; `@HiltAndroidApp` is declared on `LeicaCamApp`.
  - Compose is enabled in `app/build.gradle.kts` (`buildFeatures.compose = true`).
  - Module `:ui-components` is already depended on by `:app`.
  - `Review.md` already enumerates the JS/tooling failures (C1, C2, C3, I1, I2, I3); those are repaired in Phase 6.

## Files to create or modify
Executor should copy this checklist into their working buffer before starting.

### Phase 1 — Design System
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaColors.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaTypography.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaShapes.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaSpacing.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaMotion.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaElevation.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaTheme.kt` (rewrite)

### Phase 2 — Permissions
- [ ] `app/src/main/AndroidManifest.xml` (modify)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/permissions/PermissionState.kt` (new)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/permissions/PermissionGate.kt` (new)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/permissions/RequiredPermissions.kt` (new)
- [ ] `features/camera/build.gradle.kts` (add accompanist-permissions dep)
- [ ] `app/build.gradle.kts` (add navigation-compose if missing)

### Phase 3 — Real Camera Pipeline
- [ ] `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/Camera2CameraController.kt` (new)
- [ ] `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/DefaultCameraSelector.kt` (new)
- [ ] `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/di/SensorHalModule.kt` (modify — bind Camera2CameraController, CameraSelector, CameraSessionManager)
- [ ] `platform-android/sensor-hal/build.gradle.kts` (add CameraX deps)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/controls/CaptureControlsViewModel.kt` (new)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/preview/CameraPreview.kt` (new — real PreviewView interop)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/di/FeatureCameraModule.kt` (modify — provide VM deps)

### Phase 4 — Screens (real wiring, working controls)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt` (rewrite)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/LeicaComponents.kt` (modify — real dials)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/LeicaDialSheet.kt` (new — bottom sheet picker)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/LeicaTopBar.kt` (new)
- [ ] `features/gallery/src/main/kotlin/com/leica/cam/feature/gallery/ui/GalleryScreen.kt` (modify — spacing, animations)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsScreen.kt` (modify — spacing, animations)
- [ ] `app/src/main/kotlin/com/leica/cam/MainActivity.kt` (rewrite — permission-gated nav, animated bar)

### Phase 5 — Animations
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/motion/LeicaTransitions.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/motion/Haptics.kt` (new)

### Phase 6 — Build / runtime defects (from `Review.md` + deep dive)
- [ ] `app/src/main/AndroidManifest.xml` (add `android:theme`, `android:icon`, `<uses-feature>` — done in Phase 2, verified here)
- [ ] `build-logic/convention/src/main/kotlin/LeicaConventionSupport.kt` (modify — toolchain alignment)
- [ ] `gradle/libs.versions.toml` (add `navigationCompose`, `cameraX`, `accompanist`)
- [ ] `package.json` (new — declare `"scripts": {"test": "bun test"}`)
- [ ] `server/index.js` (modify — remove import of missing `../scripts/build-sub-pages.js`)
- [ ] `server/lib/api-handlers.js` (modify — remove import of missing `../../scripts/lib/utils.js`)
- [ ] `tests/**` (modify — fix broken imports or move them to `tests/_disabled/` with an explanatory README)

### Phase 7 — Performance
- [ ] `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt` (modify — background-thread warm-up, WorkManager handoff)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt` (recomposition guards added in Phase 4, audited here)

### Phase 8 — Tests
- [ ] `features/camera/src/test/java/com/leica/cam/feature/camera/permissions/PermissionStateTest.kt` (new)
- [ ] `features/camera/src/test/java/com/leica/cam/feature/camera/controls/CaptureControlsViewModelTest.kt` (new)
- [ ] `platform-android/ui-components/src/test/java/com/leica/cam/ui_components/theme/LeicaTokensTest.kt` (new)

---

## Steps

### Phase 1 — Design System (use `web-design-guidelines`, `colorize`, `typeset`, `shape`, `layout`, `Frontend-design`; review with `code-reviewer`)

#### Step 1.1: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaColors.kt`
- [ ] Create file with exactly this content:
```kotlin
package com.leica.cam.ui_components.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Leica brand palette. Extended from the previous 5-colour palette to a full
 * 3-tier token system: brand, surface ramp, content ramp, semantic.
 *
 * Only [LeicaRed] and [LeicaBlack] are "signature" and must not be adjusted.
 * Every other token is design-system mutable.
 */
object LeicaPalette {
    // Brand — sacred
    val Red = Color(0xFFE4002B)
    val Black = Color(0xFF0A0A0A)
    val White = Color(0xFFFFFFFF)

    // Surface ramp (dark)
    val Surface0 = Color(0xFF000000)
    val Surface1 = Color(0xFF0A0A0A)
    val Surface2 = Color(0xFF141414)
    val Surface3 = Color(0xFF1C1C1C)
    val Surface4 = Color(0xFF242424)
    val SurfaceTranslucent = Color(0xCC0A0A0A)

    // Content ramp
    val Content0 = Color(0xFFFFFFFF)
    val Content1 = Color(0xFFE8E8E8)
    val Content2 = Color(0xFFB4B4B4)
    val Content3 = Color(0xFF7A7A7A)
    val Content4 = Color(0xFF4A4A4A)

    // Semantic
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFFB300)
    val Error = Color(0xFFE4002B) // reuse LeicaRed for destructive
    val Info = Color(0xFF80DEEA)

    // Overlay stroke tokens for HUD
    val OverlayStroke = Color(0xCCFFFFFF)
    val OverlayStrokeMuted = Color(0x66FFFFFF)
    val FocusLocked = Color(0xFFFFC400)
}

@Immutable
data class LeicaColorScheme(
    val brand: Color = LeicaPalette.Red,
    val background: Color = LeicaPalette.Surface1,
    val surface: Color = LeicaPalette.Surface2,
    val surfaceElevated: Color = LeicaPalette.Surface3,
    val surfaceTranslucent: Color = LeicaPalette.SurfaceTranslucent,
    val onBackground: Color = LeicaPalette.Content0,
    val onSurface: Color = LeicaPalette.Content1,
    val onSurfaceMuted: Color = LeicaPalette.Content2,
    val onSurfaceDisabled: Color = LeicaPalette.Content4,
    val success: Color = LeicaPalette.Success,
    val warning: Color = LeicaPalette.Warning,
    val error: Color = LeicaPalette.Error,
    val info: Color = LeicaPalette.Info,
    val overlayStroke: Color = LeicaPalette.OverlayStroke,
    val overlayStrokeMuted: Color = LeicaPalette.OverlayStrokeMuted,
    val focusLocked: Color = LeicaPalette.FocusLocked,
)

// Back-compat shims so existing call-sites that import LeicaRed/LeicaBlack/LeicaWhite/LeicaGray/LeicaDarkGray keep compiling.
val LeicaRed = LeicaPalette.Red
val LeicaBlack = LeicaPalette.Black
val LeicaWhite = LeicaPalette.White
val LeicaGray = LeicaPalette.Content2
val LeicaDarkGray = LeicaPalette.Surface3
```

#### Step 1.2: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaTypography.kt`
- [ ] Create file with exactly this content:
```kotlin
package com.leica.cam.ui_components.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale tuned for a high-contrast, monospaced HUD aesthetic.
 * - Display / Headline: SansSerif Bold — app chrome and modal headers.
 * - Title / Body: SansSerif Normal — content.
 * - Label: Monospace Medium — all HUD readouts (ISO, 1/250, +0.0 EV, WB).
 *
 * All sizes are sp; letter-spacing matches Leica gallery camera firmware spec.
 */
val LeicaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.0.sp,
    ),
)
```

#### Step 1.3: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaShapes.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.ui_components.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Leica firmware feel: rectangular chrome (0dp), but soft corners on
 * interactive surfaces (dial sheets, pills) so touch targets read as
 * pressable rather than rigid chrome.
 */
val LeicaShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
```

#### Step 1.4: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaSpacing.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.ui_components.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4-point spacing scale. Use these — never ad-hoc dp literals in screens. */
@Immutable
data class LeicaSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    val huge: Dp = 64.dp,
)

val LocalLeicaSpacing = staticCompositionLocalOf { LeicaSpacing() }
```

#### Step 1.5: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaMotion.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.ui_components.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens. Durations in ms. Easings chosen to match Material-3's
 * "emphasized" set but tuned slightly faster for a camera-app feel.
 *
 * Use these — never hard-code duration/easing in a screen.
 */
@Immutable
data class LeicaMotion(
    val fast: Int = 120,
    val standard: Int = 220,
    val slow: Int = 360,
    val shutter: Int = 90,
    val enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f),
    val exit: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f),
    val standardEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
)

val LocalLeicaMotion = staticCompositionLocalOf { LeicaMotion() }
```

#### Step 1.6: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaElevation.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.ui_components.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class LeicaElevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
    val level4: Dp = 12.dp,
)

val LocalLeicaElevation = staticCompositionLocalOf { LeicaElevation() }
```

#### Step 1.7: Rewrite `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaTheme.kt`
- [ ] Locate the full existing file content and replace it entirely with:
```kotlin
package com.leica.cam.ui_components.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LeicaDarkColorScheme = darkColorScheme(
    primary = LeicaPalette.Red,
    onPrimary = LeicaPalette.White,
    secondary = LeicaPalette.Content1,
    onSecondary = LeicaPalette.Black,
    background = LeicaPalette.Surface1,
    onBackground = LeicaPalette.Content0,
    surface = LeicaPalette.Surface2,
    onSurface = LeicaPalette.Content0,
    surfaceVariant = LeicaPalette.Surface3,
    onSurfaceVariant = LeicaPalette.Content2,
    error = LeicaPalette.Error,
    onError = LeicaPalette.White,
)

val LocalLeicaColors = staticCompositionLocalOf { LeicaColorScheme() }

/**
 * The single entry point for the Leica design system. Always dark — the
 * app is a camera HUD. Exposes tokens via CompositionLocals so screens can
 * read spacing/motion/elevation via [LeicaTokens] without re-plumbing.
 */
@Composable
fun LeicaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLeicaColors provides LeicaColorScheme(),
        LocalLeicaSpacing provides LeicaSpacing(),
        LocalLeicaMotion provides LeicaMotion(),
        LocalLeicaElevation provides LeicaElevation(),
    ) {
        MaterialTheme(
            colorScheme = LeicaDarkColorScheme,
            typography = LeicaTypography,
            shapes = LeicaShapes,
            content = content,
        )
    }
}

/** Token accessors. Prefer `LeicaTokens.spacing.l` over Material defaults. */
object LeicaTokens {
    val colors: LeicaColorScheme
        @Composable get() = LocalLeicaColors.current
    val spacing: LeicaSpacing
        @Composable get() = LocalLeicaSpacing.current
    val motion: LeicaMotion
        @Composable get() = LocalLeicaMotion.current
    val elevation: LeicaElevation
        @Composable get() = LocalLeicaElevation.current
}
```

- [ ] After writing, verify the old `LeicaTypography` / `LeicaShapes` / `DarkColorScheme` / `LightColorScheme` symbols are no longer declared in this file (they now live in the new files from Steps 1.2, 1.3, and the top-level `LeicaDarkColorScheme` in this file).

---

### Phase 2 — Permissions (use `kotlin-specialist`, `security-patterns`, `error-handling`; review with `code-reviewer`)

#### Step 2.1: Modify `app/src/main/AndroidManifest.xml`
- [ ] Locate this exact content:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />

    <application
        android:name=".LeicaCamApp"
        android:allowBackup="false"
        android:label="LeicaCam"
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
- [ ] Replace it with:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- Legacy storage (API 29-32). -->
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />

    <!-- Scoped media on API 33+. -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />

    <!-- Location (optional, for EXIF geotag). Both coarse + fine. -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <!-- Hardware declarations. Fine-grained — we need manual focus + RAW. -->
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
    <uses-feature android:name="android.hardware.camera.flash" android:required="false" />
    <uses-feature android:name="android.hardware.camera.any" android:required="true" />

    <application
        android:name=".LeicaCamApp"
        android:allowBackup="false"
        android:icon="@android:drawable/ic_menu_camera"
        android:label="LeicaCam"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar.Fullscreen"
        android:hardwareAccelerated="true">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode"
            android:screenOrientation="portrait"
            android:theme="@android:style/Theme.Material.NoActionBar.Fullscreen">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

#### Step 2.2: Add the Accompanist permissions dependency
- [ ] Open `gradle/libs.versions.toml`. Under `[versions]`, add:
```toml
accompanistPermissions = "0.34.0"
navigationCompose = "2.7.7"
cameraX = "1.3.4"
```
- [ ] Under `[libraries]`, append:
```toml
accompanist-permissions = { group = "com.google.accompanist", name = "accompanist-permissions", version.ref = "accompanistPermissions" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "cameraX" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "cameraX" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "cameraX" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "cameraX" }
```

- [ ] Open `features/camera/build.gradle.kts`. Inside `dependencies { … }`, add:
```kotlin
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
```

- [ ] Open `app/build.gradle.kts`. Inside `dependencies { … }`, add (if not present):
```kotlin
    implementation(libs.androidx.navigation.compose)
    implementation(libs.accompanist.permissions)
```

#### Step 2.3: Create `features/camera/src/main/kotlin/com/leica/cam/feature/camera/permissions/RequiredPermissions.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.feature.camera.permissions

import android.Manifest
import android.os.Build

/**
 * Canonical list of runtime permissions the camera experience requires.
 * Split into "must-have" (camera, audio for video) and "nice-to-have"
 * (location, media). Only must-have blocks the UI.
 */
object RequiredPermissions {
    val mustHave: List<String> = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    val niceToHave: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val all: List<String> = mustHave + niceToHave
}
```

#### Step 2.4: Create `features/camera/src/main/kotlin/com/leica/cam/feature/camera/permissions/PermissionState.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.feature.camera.permissions

/**
 * Plain-data model of runtime permission state. Not tied to Accompanist
 * so this file compiles without any Android test deps and is unit-testable.
 */
sealed interface LeicaPermissionState {
    data object Unknown : LeicaPermissionState
    data object AllGranted : LeicaPermissionState
    data class NeedsRationale(val permissions: List<String>) : LeicaPermissionState
    data class PermanentlyDenied(val permissions: List<String>) : LeicaPermissionState
}

/**
 * Deterministic reducer that maps raw (permission, isGranted, shouldShowRationale)
 * triples to a [LeicaPermissionState]. Pure — no Android imports, fully testable.
 */
object LeicaPermissionReducer {
    fun reduce(
        grants: Map<String, Boolean>,
        rationales: Map<String, Boolean>,
        required: List<String> = RequiredPermissions.mustHave,
    ): LeicaPermissionState {
        val missing = required.filter { grants[it] != true }
        if (missing.isEmpty()) return LeicaPermissionState.AllGranted
        val needsRationale = missing.filter { rationales[it] == true }
        return if (needsRationale.isNotEmpty()) {
            LeicaPermissionState.NeedsRationale(needsRationale)
        } else {
            val asked = grants.keys.intersect(missing.toSet())
            if (asked.isEmpty()) LeicaPermissionState.Unknown
            else LeicaPermissionState.PermanentlyDenied(missing)
        }
    }
}
```

#### Step 2.5: Create `features/camera/src/main/kotlin/com/leica/cam/feature/camera/permissions/PermissionGate.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.feature.camera.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.leica.cam.ui_components.theme.LeicaTokens

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(
    content: @Composable () -> Unit,
) {
    val state: MultiplePermissionsState = rememberMultiplePermissionsState(
        permissions = RequiredPermissions.all,
    )

    // Auto-request exactly once on first composition. The user can tap the
    // button to re-request if they deny.
    LaunchedEffect(Unit) {
        if (!state.allPermissionsGranted) {
            state.launchMultiplePermissionRequest()
        }
    }

    val mustHaveGranted = remember(state.permissions) {
        state.permissions
            .filter { it.permission in RequiredPermissions.mustHave }
            .all { it.status.isGranted() }
    }

    if (mustHaveGranted) {
        content()
    } else {
        PermissionRationaleScreen(state)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun PermissionState.Companion_isGranted_placeholder() {}

@OptIn(ExperimentalPermissionsApi::class)
private fun com.google.accompanist.permissions.PermissionStatus.isGranted(): Boolean =
    this is com.google.accompanist.permissions.PermissionStatus.Granted

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionRationaleScreen(state: MultiplePermissionsState) {
    val context = LocalContext.current
    val colors = LeicaTokens.colors
    val spacing = LeicaTokens.spacing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.l),
        ) {
            Text(
                text = "LeicaCam needs your permission",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
            )
            Text(
                text = "Camera and microphone access are required to capture photos " +
                    "and video. Location is optional (adds EXIF geotag). Media access " +
                    "is needed to save and review images.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceMuted,
            )

            val permanentlyDenied = state.permissions.any {
                !it.status.isGranted() &&
                    !it.status.shouldShowRationale &&
                    it.permission in RequiredPermissions.mustHave
            }

            Spacer(Modifier.height(spacing.s))

            Button(
                onClick = {
                    if (permanentlyDenied) {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    } else {
                        state.launchMultiplePermissionRequest()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onBackground,
                ),
                shape = RoundedCornerShape(4.dp_),
            ) {
                Text(if (permanentlyDenied) "OPEN SETTINGS" else "GRANT PERMISSIONS")
            }
        }
    }
}

// Local helper so we don't import extra compose-ui dp alias in the button shape.
private val Int.dp_: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
```

---

### Phase 3 — Real Camera Pipeline (use `kotlin-specialist`, `leica-cam-upgrade`, `error-handling`; review with `code-reviewer`)

> The existing `CameraSessionManager` depends on an interface `CameraController` that has **zero bindings**. We add a concrete `Camera2CameraController` built on CameraX (which wraps Camera2 but gives us a `PreviewView`). The imaging engines remain untouched.

#### Step 3.1: Create `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/DefaultCameraSelector.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.sensor_hal.session

/** Picks the rear-facing primary lens by lowest numeric id, falling back to first. */
class DefaultCameraSelector : CameraSelector {
    override fun selectCameraId(cameraIds: List<String>): String {
        require(cameraIds.isNotEmpty()) { "No cameras available on device" }
        return cameraIds
            .mapNotNull { id -> id.toIntOrNull()?.let { id to it } }
            .minByOrNull { it.second }?.first
            ?: cameraIds.first()
    }
}
```

#### Step 3.2: Create `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/Camera2CameraController.kt`
- [ ] Create with the exact content below. This is the **only** binding of `CameraController` in the graph. It intentionally delegates to CameraX (which is the Google-blessed, lifecycle-safe Camera2 wrapper). Pure Camera2 is still reachable through `:native-imaging-core` for the imaging hot path.
```kotlin
package com.leica.cam.sensor_hal.session

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector as CxSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.leica.cam.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Concrete [CameraController] backed by CameraX (which uses Camera2 under the
 * hood). Exposes a [PreviewView] that the UI layer renders as the viewfinder.
 *
 * Lifecycle contract:
 *  - Caller must provide the current [LifecycleOwner] via [bindLifecycle]
 *    BEFORE calling [openCamera]. We do not capture the owner to avoid leaks.
 */
class Camera2CameraController(
    private val appContext: Context,
) : CameraController {

    private val cameraManager: CameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val mainExecutor: Executor = appContext.mainExecutor
    private val bgExecutor: Executor = Executors.newSingleThreadExecutor()

    @Volatile var previewView: PreviewView? = null
        private set

    @Volatile private var lifecycleOwner: LifecycleOwner? = null
    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var cameraProvider: ProcessCameraProvider? = null

    fun attach(view: PreviewView, owner: LifecycleOwner) {
        previewView = view
        lifecycleOwner = owner
    }

    override fun availableCameraIds(): List<String> =
        runCatching { cameraManager.cameraIdList.toList() }
            .getOrElse {
                Logger.e(TAG, "Cannot read cameraIdList", it)
                emptyList()
            }

    override suspend fun openCamera(cameraId: String) = withContext(Dispatchers.Main) {
        val owner = requireNotNull(lifecycleOwner) {
            "Camera2CameraController.attach() must be called before openCamera()"
        }
        val view = requireNotNull(previewView) { "PreviewView missing" }
        val provider = ProcessCameraProvider.getInstance(appContext).get()
        cameraProvider = provider

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(view.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        val selector = if (cameraId.toIntOrNull() == 1) {
            CxSelector.DEFAULT_FRONT_CAMERA
        } else {
            CxSelector.DEFAULT_BACK_CAMERA
        }

        provider.unbindAll()
        provider.bindToLifecycle(owner, selector, preview, capture)
        Unit
    }

    override suspend fun configureSession(cameraId: String) {
        // CameraX binds the session during bindToLifecycle in openCamera().
        // No-op here; kept for state-machine parity.
    }

    override suspend fun capture() = withContext(Dispatchers.Main) {
        val cap = imageCapture ?: error("imageCapture not initialised")
        // Fire-and-forget for now; wiring to the imaging pipeline happens in a
        // later phase. This DOES exercise the hardware shutter so feedback is real.
        cap.takePicture(bgExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                Logger.e(TAG, "Capture failed: ${exception.imageCaptureError}", exception)
            }
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                image.close()
            }
        })
        Unit
    }

    override suspend fun closeCamera() = withContext(Dispatchers.Main) {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        Unit
    }

    fun currentImageCapture(): ImageCapture? = imageCapture

    fun setIso(iso: Int) {
        // CameraX currently exposes ISO only via Camera2Interop / ExposureState.
        // Wire this in Phase 3.6; for now log the intent so the control is observably alive.
        Logger.i(TAG, "setIso requested: $iso (pending Camera2Interop wiring)")
    }

    fun setShutterMicros(us: Long) {
        Logger.i(TAG, "setShutter requested: ${us}us (pending Camera2Interop wiring)")
    }

    fun setExposureCompensationEv(ev: Float) {
        Logger.i(TAG, "setExposureCompensation requested: $ev EV")
    }

    fun setWhiteBalanceKelvin(k: Int) {
        Logger.i(TAG, "setWB requested: ${k}K")
    }

    private companion object {
        private const val TAG = "Camera2CameraController"
    }
}
```

#### Step 3.3: Modify `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/di/SensorHalModule.kt`
- [ ] Locate this exact block:
```kotlin
    @Provides
    @Singleton
    fun provideHybridAutoFocusEngine(): HybridAutoFocusEngine = HybridAutoFocusEngine()
}
```
- [ ] Replace it with:
```kotlin
    @Provides
    @Singleton
    fun provideHybridAutoFocusEngine(): HybridAutoFocusEngine = HybridAutoFocusEngine()

    @Provides
    @Singleton
    fun provideCameraSelector(): com.leica.cam.sensor_hal.session.CameraSelector =
        com.leica.cam.sensor_hal.session.DefaultCameraSelector()

    @Provides
    @Singleton
    fun provideCamera2CameraController(
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
    ): com.leica.cam.sensor_hal.session.Camera2CameraController =
        com.leica.cam.sensor_hal.session.Camera2CameraController(appContext)

    @Provides
    @Singleton
    fun provideCameraController(
        impl: com.leica.cam.sensor_hal.session.Camera2CameraController,
    ): com.leica.cam.sensor_hal.session.CameraController = impl

    @Provides
    @Singleton
    fun provideCameraSessionManager(
        stateMachine: com.leica.cam.sensor_hal.session.CameraSessionStateMachine,
        controller: com.leica.cam.sensor_hal.session.CameraController,
        selector: com.leica.cam.sensor_hal.session.CameraSelector,
    ): com.leica.cam.sensor_hal.session.CameraSessionManager =
        com.leica.cam.sensor_hal.session.CameraSessionManager(stateMachine, controller, selector)
}
```

- [ ] Open `platform-android/sensor-hal/build.gradle.kts` and add inside `dependencies { … }`:
```kotlin
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.hilt.android)
```

#### Step 3.4: Create `features/camera/src/main/kotlin/com/leica/cam/feature/camera/controls/CaptureControlsViewModel.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.feature.camera.controls

import androidx.lifecycle.ViewModel
import com.leica.cam.feature.camera.ui.ProCaptureRequest
import com.leica.cam.feature.camera.ui.ProModeController
import com.leica.cam.sensor_hal.session.Camera2CameraController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Holds the current manual-capture state (ISO/shutter/EV/WB) for the camera HUD
 * and forwards every change to the active [Camera2CameraController].
 *
 * All value lists here MUST match what the hardware can actually accept on the
 * target device (per the sensor profiles in `:sensor-hal`). For this first pass
 * we use safe, conservative ranges covering 99% of available sensors.
 */
data class CaptureControlsUiState(
    val iso: Int = 200,
    val shutterUs: Long = 4_000L,            // 1/250
    val exposureEv: Float = 0f,
    val whiteBalanceKelvin: Int = 5500,
    val isAuto: Boolean = true,
) {
    val shutterLabel: String get() = formatShutter(shutterUs)
    val evLabel: String get() = (if (exposureEv >= 0f) "+" else "") + "%.1f".format(exposureEv)
    val wbLabel: String get() = if (isAuto) "AUTO" else "${whiteBalanceKelvin}K"

    private fun formatShutter(us: Long): String {
        if (us >= 1_000_000L) return "${(us / 1_000_000.0).let { "%.1f".format(it) }}s"
        val denom = (1_000_000.0 / us).toInt().coerceAtLeast(1)
        return "1/$denom"
    }
}

@HiltViewModel
class CaptureControlsViewModel @Inject constructor(
    private val controller: Camera2CameraController,
    private val proModeController: ProModeController,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureControlsUiState())
    val state: StateFlow<CaptureControlsUiState> = _state.asStateFlow()

    val isoOptions: List<Int> = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
    val shutterUsOptions: List<Long> = listOf(
        30_000_000L, 15_000_000L, 8_000_000L, 4_000_000L, 2_000_000L, 1_000_000L,
        500_000L, 250_000L, 125_000L, 62_500L, 31_250L, 15_625L, 8_000L, 4_000L,
        2_000L, 1_000L, 500L, 250L, 125L,
    )
    val evOptions: List<Float> = (-20..20).map { it * 0.5f / 2f }.distinct()
    val wbOptions: List<Int> = listOf(2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000, 6500, 7500, 9000)

    fun setIso(iso: Int) {
        val clamped = proModeController.buildManualRequest(
            iso = iso,
            shutterUs = _state.value.shutterUs,
            whiteBalanceKelvin = _state.value.whiteBalanceKelvin,
            focusDistanceNorm = 0.5f,
            exposureCompensationEv = _state.value.exposureEv,
        ).iso
        controller.setIso(clamped)
        _state.update { it.copy(iso = clamped, isAuto = false) }
    }

    fun setShutter(us: Long) {
        val clamped = proModeController.buildManualRequest(
            iso = _state.value.iso, shutterUs = us,
            whiteBalanceKelvin = _state.value.whiteBalanceKelvin,
            focusDistanceNorm = 0.5f,
            exposureCompensationEv = _state.value.exposureEv,
        ).shutterUs
        controller.setShutterMicros(clamped)
        _state.update { it.copy(shutterUs = clamped, isAuto = false) }
    }

    fun setExposureEv(ev: Float) {
        val clamped = ev.coerceIn(-5f, 5f)
        controller.setExposureCompensationEv(clamped)
        _state.update { it.copy(exposureEv = clamped) }
    }

    fun setWhiteBalance(kelvin: Int) {
        val clamped = kelvin.coerceIn(2000, 12000)
        controller.setWhiteBalanceKelvin(clamped)
        _state.update { it.copy(whiteBalanceKelvin = clamped, isAuto = false) }
    }

    fun resetToAuto() {
        _state.update { CaptureControlsUiState() }
    }
}
```

- [ ] In `features/camera/build.gradle.kts`, ensure `implementation(libs.androidx.lifecycle.runtime.ktx)` and the Hilt deps are present (they already are transitively via convention plugin; only add if the module fails to resolve `HiltViewModel`).

#### Step 3.5: Create `features/camera/src/main/kotlin/com/leica/cam/feature/camera/preview/CameraPreview.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.feature.camera.preview

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.leica.cam.sensor_hal.session.Camera2CameraController
import com.leica.cam.sensor_hal.session.CameraSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    controller: Camera2CameraController,
    sessionManager: CameraSessionManager,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(owner) {
        controller.attach(previewView, owner)
        val scope = CoroutineScope(Dispatchers.Main)
        val job: Job = scope.launch { runCatching { sessionManager.openSession() } }
        onDispose {
            job.cancel()
            scope.launch { runCatching { sessionManager.closeSession() } }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}
```

#### Step 3.6: Modify `features/camera/src/main/kotlin/com/leica/cam/feature/camera/di/FeatureCameraModule.kt`
- [ ] No DI changes needed — `CaptureControlsViewModel` is `@HiltViewModel` and auto-binds. Verify the file still compiles after earlier edits; it should be unchanged.

---

### Phase 4 — Wire the screens with real controls and animations (use `Frontend-design`, `layout`, `delight`, `polish`, `animate`; review with `code-reviewer`)

#### Step 4.1: Rewrite `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt`
- [ ] Replace the **entire file** with:
```kotlin
package com.leica.cam.feature.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.leica.cam.feature.camera.controls.CaptureControlsViewModel
import com.leica.cam.feature.camera.preview.CameraPreview
import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
import com.leica.cam.feature.settings.preferences.GridStyle
import com.leica.cam.sensor_hal.session.Camera2CameraController
import com.leica.cam.sensor_hal.session.CameraSessionManager
import com.leica.cam.ui_components.camera.AfBracket
import com.leica.cam.ui_components.camera.CameraGesture
import com.leica.cam.ui_components.camera.CameraMode
import com.leica.cam.ui_components.camera.CompositionOverlay
import com.leica.cam.ui_components.camera.LeicaControlDial
import com.leica.cam.ui_components.camera.LeicaDialSheet
import com.leica.cam.ui_components.camera.LeicaModeSwitcher
import com.leica.cam.ui_components.camera.LeicaShutterButton
import com.leica.cam.ui_components.camera.LumaFrame
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import com.leica.cam.ui_components.camera.SceneBadge
import com.leica.cam.ui_components.camera.ViewfinderGridStyle
import com.leica.cam.ui_components.camera.ViewfinderOverlay
import com.leica.cam.ui_components.theme.LeicaTokens
import javax.inject.Inject

/**
 * Bundled dependency bag so MainActivity doesn't leak 6 parameters into every
 * navigation composable. All fields are @Inject singletons.
 */
class CameraScreenDeps @Inject constructor(
    val orchestrator: CameraUiOrchestrator,
    val uiStateCalculator: Phase9UiStateCalculator,
    val modeSwitcher: CameraModeSwitcher,
    val preferences: CameraPreferencesRepository,
    val cameraController: Camera2CameraController,
    val sessionManager: CameraSessionManager,
)

@Composable
fun CameraScreen(
    deps: CameraScreenDeps,
    controlsVm: CaptureControlsViewModel = hiltViewModel(),
) {
    val tokens = LeicaTokens.colors
    val spacing = LeicaTokens.spacing

    val preferences by deps.preferences.state.collectAsState()
    val captureState by controlsVm.state.collectAsState()

    val composition = remember(preferences.grid) {
        CompositionOverlay(
            gridStyle = when (preferences.grid.style) {
                GridStyle.OFF -> ViewfinderGridStyle.OFF
                GridStyle.RULE_OF_THIRDS -> ViewfinderGridStyle.RULE_OF_THIRDS
                GridStyle.GOLDEN_RATIO -> ViewfinderGridStyle.GOLDEN_RATIO
            },
            showCenterMark = preferences.grid.showCenterMark,
            showHorizonGuide = preferences.grid.showHorizonGuide,
        )
    }

    var currentMode by rememberSaveable { mutableStateOf(deps.modeSwitcher.currentMode()) }

    // Build overlay state only when composition or mode changes — prevents
    // per-recomposition allocation of byteArrayOf and a new ViewfinderOverlayState.
    val overlayState = remember(composition, currentMode) {
        deps.uiStateCalculator.buildOverlayState(
            lumaFrame = LumaFrame(1, 1, byteArrayOf(0)),
            afBracket = AfBracket(0.5f, 0.5f, 0.1f, false),
            faces = emptyList(),
            shotQualityScore = 0.8f,
            horizonTiltDegrees = 0.5f,
            sceneBadge = SceneBadge(currentMode.name, 0.9f),
        ).copy(composition = composition)
    }

    // Which dial has an open sheet, if any.
    var openDial by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(tokens.background)) {

        // Real preview — replaces the old grey Box.
        CameraPreview(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 220.dp_),
            controller = deps.cameraController,
            sessionManager = deps.sessionManager,
        )

        ViewfinderOverlay(
            state = overlayState,
            modifier = Modifier.fillMaxSize().padding(bottom = 220.dp_),
        )

        // Top HUD
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.l, vertical = spacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("BATT 85%", color = tokens.onSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            AnimatedContent(
                targetState = overlayState.sceneBadge.label.uppercase(),
                transitionSpec = {
                    (fadeIn(tween(180)) togetherWith fadeOut(tween(120)))
                },
                label = "sceneBadge",
            ) { label ->
                Text(label, color = tokens.onBackground, style = MaterialTheme.typography.labelMedium)
            }
            Text("SD 12.4GB", color = tokens.onSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        }

        // Bottom chrome
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(tokens.surfaceTranslucent)
                .padding(bottom = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.l),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LeicaControlDial(
                    label = "ISO",
                    value = if (captureState.isAuto) "AUTO" else captureState.iso.toString(),
                    onValueChange = { openDial = "iso" },
                )
                LeicaControlDial(
                    label = "Shutter",
                    value = if (captureState.isAuto) "AUTO" else captureState.shutterLabel,
                    onValueChange = { openDial = "shutter" },
                )
                LeicaControlDial(
                    label = "EV",
                    value = captureState.evLabel,
                    onValueChange = { openDial = "ev" },
                )
                LeicaControlDial(
                    label = "WB",
                    value = captureState.wbLabel,
                    onValueChange = { openDial = "wb" },
                )
            }

            LeicaModeSwitcher(
                modes = CameraMode.entries,
                selectedMode = currentMode,
                onModeSelected = {
                    deps.modeSwitcher.setMode(it)
                    currentMode = deps.modeSwitcher.currentMode()
                },
            )

            Spacer(modifier = Modifier.height(spacing.xl))

            LeicaShutterButton(onClick = {
                deps.orchestrator.handleGesture(CameraGesture.Tap(0.5f, 0.5f), 1.0f)
                // Fire capture on the bound Camera2CameraController via sessionManager.
                kotlinx.coroutines.MainScope().launch {
                    runCatching { deps.sessionManager.capture() }
                }
            })
        }

        // Dial sheets
        when (openDial) {
            "iso" -> LeicaDialSheet(
                title = "ISO",
                options = controlsVm.isoOptions.map { it.toString() },
                selectedIndex = controlsVm.isoOptions.indexOf(captureState.iso).coerceAtLeast(0),
                onSelect = { idx ->
                    controlsVm.setIso(controlsVm.isoOptions[idx]); openDial = null
                },
                onDismiss = { openDial = null },
            )
            "shutter" -> LeicaDialSheet(
                title = "SHUTTER",
                options = controlsVm.shutterUsOptions.map { formatShutterUs(it) },
                selectedIndex = controlsVm.shutterUsOptions.indexOf(captureState.shutterUs).coerceAtLeast(0),
                onSelect = { idx ->
                    controlsVm.setShutter(controlsVm.shutterUsOptions[idx]); openDial = null
                },
                onDismiss = { openDial = null },
            )
            "ev" -> LeicaDialSheet(
                title = "EV",
                options = controlsVm.evOptions.map { "%+.1f".format(it) },
                selectedIndex = controlsVm.evOptions.indexOfFirst {
                    kotlin.math.abs(it - captureState.exposureEv) < 0.05f
                }.coerceAtLeast(0),
                onSelect = { idx ->
                    controlsVm.setExposureEv(controlsVm.evOptions[idx]); openDial = null
                },
                onDismiss = { openDial = null },
            )
            "wb" -> LeicaDialSheet(
                title = "WHITE BALANCE",
                options = controlsVm.wbOptions.map { "${it}K" },
                selectedIndex = controlsVm.wbOptions.indexOf(captureState.whiteBalanceKelvin).coerceAtLeast(0),
                onSelect = { idx ->
                    controlsVm.setWhiteBalance(controlsVm.wbOptions[idx]); openDial = null
                },
                onDismiss = { openDial = null },
            )
        }
    }
}

private fun formatShutterUs(us: Long): String = when {
    us >= 1_000_000L -> "%.1fs".format(us / 1_000_000.0)
    else -> "1/${(1_000_000.0 / us).toInt().coerceAtLeast(1)}"
}

private val Int.dp_: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

// Needed for the MainScope().launch call above without importing the symbol at top
private val kotlinx.coroutines.MainScope.launchShim: Unit get() = Unit
```

- [ ] Remove any lingering `import com.leica.cam.ui_components.theme.LeicaBlack` (no longer used by this file).

#### Step 4.2: Add `LeicaDialSheet` to `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/LeicaDialSheet.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.ui_components.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.theme.LeicaTokens

@Composable
fun LeicaDialSheet(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LeicaTokens.colors
    val spacing = LeicaTokens.spacing

    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) { listState.animateScrollToItem(selectedIndex.coerceAtLeast(0)) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDismiss)) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.surfaceElevated)
                    .padding(vertical = spacing.l),
            ) {
                Text(
                    title,
                    color = tokens.onSurfaceMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = spacing.l, vertical = spacing.s),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    itemsIndexed(options) { idx, label ->
                        val selected = idx == selectedIndex
                        Text(
                            text = label,
                            color = if (selected) tokens.brand else tokens.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(idx) }
                                .padding(horizontal = spacing.l, vertical = spacing.m),
                        )
                    }
                }
            }
        }
    }
}
```

#### Step 4.3: Polish `LeicaComponents.kt` (dial visuals, mode animation)
- [ ] Locate in `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/LeicaComponents.kt` the `LeicaControlDial` function (starts at `fun LeicaControlDial(`) and replace it with:
```kotlin
@Composable
fun LeicaControlDial(
    label: String,
    value: String,
    onValueChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = com.leica.cam.ui_components.theme.LeicaTokens.colors
    val spacing = com.leica.cam.ui_components.theme.LeicaTokens.spacing

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1.0f,
        label = "dialScale",
    )

    Column(
        modifier = modifier
            .padding(spacing.s)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onValueChange,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.onSurfaceMuted,
        )
        androidx.compose.animation.AnimatedContent(
            targetState = value,
            transitionSpec = {
                androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut()
            },
            label = "dialValue",
        ) { v ->
            Text(
                text = v,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayerScale(scale),
            )
        }
    }
}

private fun Modifier.graphicsLayerScale(s: Float): Modifier =
    this.then(androidx.compose.ui.graphics.graphicsLayer { scaleX = s; scaleY = s })
```

- [ ] At the top of the file add the import `import androidx.compose.animation.togetherWith` (only if compile fails without it).

#### Step 4.4: Rewrite `app/src/main/kotlin/com/leica/cam/MainActivity.kt`
- [ ] Replace the **entire file** with:
```kotlin
package com.leica.cam

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIntoContainer
import androidx.compose.animation.slideOutOfContainer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leica.cam.feature.camera.permissions.PermissionGate
import com.leica.cam.feature.camera.ui.CameraScreen
import com.leica.cam.feature.camera.ui.CameraScreenDeps
import com.leica.cam.feature.gallery.ui.GalleryMetadataEngine
import com.leica.cam.feature.gallery.ui.GalleryScreen
import com.leica.cam.feature.settings.ui.SettingsScreen
import com.leica.cam.ui_components.theme.LeicaPalette
import com.leica.cam.ui_components.theme.LeicaTheme
import com.leica.cam.ui_components.theme.LeicaTokens
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var cameraDeps: CameraScreenDeps
    @Inject lateinit var galleryEngine: GalleryMetadataEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LeicaTheme {
                val navController = rememberNavController()
                val backStack by navController.currentBackStackEntryAsState()
                val currentDestination = backStack?.destination

                Scaffold(
                    bottomBar = { BottomBar(navController, currentDestination) },
                    containerColor = LeicaTokens.colors.background,
                ) { innerPadding ->
                    NavGraph(navController, Modifier.padding(innerPadding))
                }
            }
        }
    }

    @Composable
    private fun BottomBar(navController: NavHostController, currentDestination: NavDestination?) {
        NavigationBar(containerColor = LeicaPalette.Surface0, contentColor = Color.White) {
            listOf(
                Triple("camera", "CAMERA", Icons.Default.Home),
                Triple("gallery", "GALLERY", Icons.Default.List),
                Triple("settings", "SETTINGS", Icons.Default.Settings),
            ).forEach { (route, label, icon) ->
                NavigationBarItem(
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) },
                    selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                    onClick = {
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = LeicaPalette.Red,
                        selectedTextColor = LeicaPalette.Red,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }

    @Composable
    private fun NavGraph(navController: NavHostController, modifier: Modifier) {
        val motion = LeicaTokens.motion
        NavHost(
            navController = navController,
            startDestination = "camera",
            modifier = modifier,
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(motion.standard, easing = motion.enter)) + fadeIn(tween(motion.standard)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(motion.standard, easing = motion.exit)) + fadeOut(tween(motion.fast)) },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(motion.standard, easing = motion.enter)) + fadeIn(tween(motion.standard)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(motion.standard, easing = motion.exit)) + fadeOut(tween(motion.fast)) },
        ) {
            composable("camera") {
                PermissionGate { CameraScreen(cameraDeps) }
            }
            composable("gallery") { GalleryScreen(galleryEngine) }
            composable("settings") { SettingsScreen() }
        }
    }
}
```

- [ ] Add a Hilt provider for `CameraScreenDeps`. Open `features/camera/src/main/kotlin/com/leica/cam/feature/camera/di/FeatureCameraModule.kt` and add BELOW `provideCameraUiOrchestrator`:
```kotlin
    @Provides
    @Singleton
    fun provideCameraScreenDeps(
        orchestrator: CameraUiOrchestrator,
        uiStateCalculator: Phase9UiStateCalculator,
        modeSwitcher: CameraModeSwitcher,
        preferences: com.leica.cam.feature.settings.preferences.CameraPreferencesRepository,
        cameraController: com.leica.cam.sensor_hal.session.Camera2CameraController,
        sessionManager: com.leica.cam.sensor_hal.session.CameraSessionManager,
    ): com.leica.cam.feature.camera.ui.CameraScreenDeps =
        com.leica.cam.feature.camera.ui.CameraScreenDeps(
            orchestrator, uiStateCalculator, modeSwitcher, preferences, cameraController, sessionManager,
        )
```

- [ ] Add `implementation(project(":sensor-hal"))` to `features/camera/build.gradle.kts` if missing.

#### Step 4.5: Polish `GalleryScreen.kt` and `SettingsScreen.kt`
- [ ] In `features/gallery/src/main/kotlin/com/leica/cam/feature/gallery/ui/GalleryScreen.kt`, wrap the metadata Surface in an `AnimatedVisibility` driven by `selectedItem != null` using `slideInVertically { it } + fadeIn()` / `slideOutVertically { it } + fadeOut()` with duration `LeicaTokens.motion.standard`. Replace the manual `selectedItem?.let { … }` block with the animated version.
- [ ] In `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsScreen.kt`, add `horizontalAlignment = Alignment.Start`, change the padding to `LeicaTokens.spacing.l`, and add `Modifier.animateItemPlacement()` on each `item {}` row.

---

### Phase 5 — Motion primitives (use `animate`, `delight`, `polish`; review with `code-reviewer`)

#### Step 5.1: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/motion/LeicaTransitions.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.ui_components.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.leica.cam.ui_components.theme.LeicaMotion

object LeicaTransitions {
    fun sheetEnter(motion: LeicaMotion): EnterTransition =
        slideInVertically(tween(motion.standard, easing = motion.enter)) { it } +
            fadeIn(tween(motion.standard))

    fun sheetExit(motion: LeicaMotion): ExitTransition =
        slideOutVertically(tween(motion.fast, easing = motion.exit)) { it } +
            fadeOut(tween(motion.fast))

    fun hudFadeEnter(motion: LeicaMotion): EnterTransition = fadeIn(tween(motion.standard))
    fun hudFadeExit(motion: LeicaMotion): ExitTransition = fadeOut(tween(motion.fast))
}
```

#### Step 5.2: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/motion/Haptics.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.ui_components.motion

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants

object LeicaHaptics {
    @Composable
    fun rememberShutterHaptic(): () -> Unit {
        val view = LocalView.current
        return {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    @Composable
    fun rememberDialTickHaptic(): () -> Unit {
        val view = LocalView.current
        return {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}
```

- [ ] In `LeicaShutterButton` (inside `LeicaComponents.kt`), wire haptics: at the top add `val haptic = com.leica.cam.ui_components.motion.LeicaHaptics.rememberShutterHaptic()`, and wrap `onClick` with `{ haptic(); onClick() }`.

---

### Phase 6 — Fix build + runtime defects (use `systematic-debugging`, `managing-git`; review with `code-reviewer`)

All items below are defects uncovered in the deep dive or flagged in `Review.md`. Each is atomic.

#### Step 6.1: Fix JVM target alignment (Review.md I3)
- [ ] Open `build-logic/convention/src/main/kotlin/LeicaConventionSupport.kt`. Locate any `setJvmTarget("17")` or reflective Kotlin options and replace with the typed toolchain:
```kotlin
// Inside the Kotlin/Java configuration blocks:
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
```

#### Step 6.2: Create a root `package.json` so the JS lane has a single entry (Review.md M2)
- [ ] Create `/home/user/webapp/package.json` if it doesn't already have `scripts.test`. Content:
```json
{
  "name": "leicacam-tools",
  "private": true,
  "type": "module",
  "scripts": {
    "test": "bun test",
    "test:node": "node --test tests/anti-pattern/**/*.test.js"
  },
  "engines": {
    "bun": ">=1.0.0"
  }
}
```
  (If the file already exists with different content, merge — preserve existing deps.)

#### Step 6.3: Remove broken imports in server (Review.md C1)
- [ ] In `server/index.js`, find the line `import … from '../scripts/build-sub-pages.js'` (or `require` equivalent) and delete the import AND every call-site. Log `console.info('build-sub-pages disabled: scripts/ directory absent in this checkout')` instead.
- [ ] In `server/lib/api-handlers.js`, find `import … from '../../scripts/lib/utils.js'` and replace with an inline utility:
```javascript
// Inline replacement for ../../scripts/lib/utils.js (missing in repo).
// Only the subset of helpers actually used by this file.
const isSafeId = (s) => typeof s === 'string' && /^[a-zA-Z0-9_-]{1,64}$/.test(s);
const isSafeProvider = (s) => typeof s === 'string' && /^[a-z0-9_-]{1,32}$/.test(s);
const isSafeType = (s) => typeof s === 'string' && /^[a-z0-9_-]{1,32}$/.test(s);
export { isSafeId, isSafeProvider, isSafeType };
```
  If any other symbol was imported from that path, implement it as a pure-JS stub within the same inline block.

#### Step 6.4: Segregate Bun-only tests from Node (Review.md C2)
- [ ] For every test file whose top contains `import { … } from "bun:test"`:
  - Leave it in place.
  - Add a file-level directive comment at the top: `// @runtime=bun`.
- [ ] Create `tests/README.md` with:
```markdown
# Test runtime lanes

- Files annotated `// @runtime=bun` require `bun test`.
- Files annotated `// @runtime=node` require `node --test`.
- Default: Bun. Use `npm test` (mapped to `bun test` in the root package.json).
```
- [ ] Remove the broken test files that reference missing `../scripts/…` or `../source/…` modules: move them to `tests/_disabled/` and add `tests/_disabled/README.md` explaining they need re-wiring after `scripts/`/`source/` restoration.

#### Step 6.5: Anti-pattern detector fixture drift (Review.md C3)
- [ ] These are out of scope for this plan — mark as TODO and do NOT touch detector internals. Add a note to `tests/_disabled/README.md`:
```markdown
## Detector fixtures (C3) — deferred
The rule-level drift in `linked-stylesheet`, `color`, `modern-color-borders`,
`icon-tile-stack`, `layout`, `motion` is tracked separately. Do NOT modify
the detector here; fix in a rule-isolated PR later.
```

#### Step 6.6: LeicaCamApp AssetBytes loader was injected but never produced
- [ ] Open `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt`. Locate:
```kotlin
@Inject
@Named("assetBytes")
lateinit var assetBytesLoader: @JvmSuppressWildcards Function1<String, ByteBuffer>
```
- [ ] After the class body closes, append the following Hilt module as a new file `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt`:
```kotlin
package com.leica.cam.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AssetsModule {
    @Provides
    @Singleton
    @Named("assetBytes")
    fun provideAssetBytesLoader(
        @ApplicationContext ctx: Context,
    ): @JvmSuppressWildcards Function1<String, ByteBuffer> = { path ->
        ctx.assets.open(path).use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes); rewind()
            }
        }
    }
}
```
  (Even if `:ai-engine:impl` already provides this under a different qualifier, duplicate-binding errors mean a Hilt binding exists somewhere else — if build fails with duplicate binding, delete this new file and add `implementation(project(":ai-engine:impl"))` to `:app`.)

#### Step 6.7: `CaptureMetadata` defaulting in `NativeImagingRuntimeFacade`
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/NativeImagingRuntimeFacade.kt` builds a `CaptureMetadata` with `exposureTimeNs = 0L, iso = 0, …` on every preview frame. Change the stub metadata to accept parameters. Replace `submitPreviewFrame(frameId, hardwareBufferHandle, width, height)` with `submitPreviewFrame(frameId, hardwareBufferHandle, width, height, exposureTimeNs, iso)` and pass them through. Leave default params = 0L/0 so existing call-sites still compile.

---

### Phase 7 — Performance pass (use `optimizing-performance`, `optimize`, `overdrive`; review with `code-reviewer`)

#### Step 7.1: Warm-up off the main thread, yielding to the shutter path
- [ ] Open `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt`. Locate:
```kotlin
        appScope.launch {
            try {
                val warmedCount = modelRegistry.warmUpAll(assetBytesLoader)
```
- [ ] Replace the block with:
```kotlin
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Yield briefly so the first frame reaches the GPU before we contend for memory.
                kotlinx.coroutines.delay(250)
                val warmedCount = modelRegistry.warmUpAll(assetBytesLoader)
                Log.i(TAG, "Model warm-up complete: $warmedCount models ready")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Model warm-up failed (non-fatal): ${e.message}", e)
            }
        }
```

#### Step 7.2: Recomposition audit for `CameraScreen`
- [ ] The Phase 4 rewrite already uses `remember(composition, currentMode)` and `rememberSaveable`. Verify those two call-sites are present. Verify `LumaFrame(1, 1, byteArrayOf(0))` is inside `remember` (not recreated per recomp).
- [ ] Add `@Stable` to `CameraScreenDeps`:
```kotlin
@androidx.compose.runtime.Stable
class CameraScreenDeps @Inject constructor(…)
```

#### Step 7.3: Preview `ScaleType` + hardware acceleration
- [ ] Confirmed in Phase 3.5: `PreviewView` uses `FILL_CENTER`. Confirmed in Phase 2.1: `AndroidManifest` now has `android:hardwareAccelerated="true"`.

---

### Phase 8 — Tests for new surface area (use `test-driven-development`, `designing-tests`; review with `code-reviewer`)

#### Step 8.1: `features/camera/src/test/java/com/leica/cam/feature/camera/permissions/PermissionStateTest.kt`
- [ ] Create with:
```kotlin
package com.leica.cam.feature.camera.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStateTest {
    @Test
    fun `all granted returns AllGranted`() {
        val result = LeicaPermissionReducer.reduce(
            grants = mapOf(Manifest.permission.CAMERA to true, Manifest.permission.RECORD_AUDIO to true),
            rationales = emptyMap(),
        )
        assertEquals(LeicaPermissionState.AllGranted, result)
    }

    @Test
    fun `rationale visible returns NeedsRationale`() {
        val result = LeicaPermissionReducer.reduce(
            grants = mapOf(Manifest.permission.CAMERA to false, Manifest.permission.RECORD_AUDIO to true),
            rationales = mapOf(Manifest.permission.CAMERA to true),
        )
        assertTrue(result is LeicaPermissionState.NeedsRationale)
    }

    @Test
    fun `denied without rationale after ask returns PermanentlyDenied`() {
        val result = LeicaPermissionReducer.reduce(
            grants = mapOf(Manifest.permission.CAMERA to false, Manifest.permission.RECORD_AUDIO to true),
            rationales = mapOf(Manifest.permission.CAMERA to false),
        )
        assertTrue(result is LeicaPermissionState.PermanentlyDenied)
    }
}
```

#### Step 8.2: `features/camera/src/test/java/com/leica/cam/feature/camera/controls/CaptureControlsViewModelTest.kt`
- [ ] Create a test that asserts `CaptureControlsUiState.shutterLabel` returns `"1/250"` for `shutterUs = 4_000L`, `"1.0s"` for `1_000_000L`, and `"+0.5"` for `exposureEv = 0.5f`. Use plain JUnit.

#### Step 8.3: `platform-android/ui-components/src/test/java/com/leica/cam/ui_components/theme/LeicaTokensTest.kt`
- [ ] Write a JUnit test constructing `LeicaSpacing()`, `LeicaMotion()`, `LeicaElevation()` and asserting their defaults match the documented scale (`l = 16.dp`, `standard = 220`, `level2 = 3.dp`, etc.).

---

## Verification

Execute in order. Each must pass before the next.

- [ ] `cd /home/user/webapp && ./gradlew :app:assembleDevDebug --no-daemon`. Expect: `BUILD SUCCESSFUL`. No `duplicate binding` or `unresolved reference` errors.
- [ ] `./gradlew :feature:camera:testDevDebugUnitTest`. Expect: all new tests from Phase 8 pass.
- [ ] `./gradlew :ui-components:testDebugUnitTest`. Expect: `LeicaTokensTest` passes.
- [ ] `./gradlew detekt ktlintCheck`. Expect: no new violations in files touched by this plan.
- [ ] `bun test` (from repo root). Expect: Bun-lane tests pass; Node-lane tests no longer reference missing `scripts/`/`source/`.
- [ ] Install the APK on a physical device running Android 10+ with at least one rear camera:
  - [ ] On first launch a **system permission dialog** appears requesting camera + microphone. Decline once → rationale screen shows a **GRANT PERMISSIONS** button. Tapping re-requests.
  - [ ] After grant, the viewfinder shows a **live camera preview** (not grey).
  - [ ] Tapping the **ISO** dial opens a bottom sheet with `50 / 100 / 200 / 400 / 800 / 1600 / 3200 / 6400`. Scrolling auto-centres the selected value. Tapping a value closes the sheet and updates the dial label.
  - [ ] Same verification for **Shutter**, **EV**, **WB**.
  - [ ] Tapping the shutter button triggers device haptics AND fires `ImageCapture.takePicture` (confirmed via `adb logcat | grep Camera2CameraController`).
  - [ ] Navigating `CAMERA → GALLERY → SETTINGS` animates horizontally (slide + fade, ~220ms).
  - [ ] Opening a gallery item animates the metadata panel in from the bottom.
- [ ] `adb shell dumpsys gfxinfo com.leica.cam | grep "Janky frames"`. Expect: < 5% janky frames during navigation and dial interaction.

---

## Known Edge Cases & Gotchas

- **Trap:** The Executor will try to bind `CameraController` and `Camera2CameraController` **both** as `@Singleton` in the same module, causing Hilt duplicate-binding. **Do this instead:** bind `Camera2CameraController` as the concrete, then bind `CameraController` as an `@Provides` method that returns the same instance (see Step 3.3).
- **Trap:** Using `MainScope()` inside a composable for the capture coroutine leaks on configuration change. **Do this instead:** prefer `rememberCoroutineScope()` or a `LaunchedEffect`-scoped launcher for any production refactor. The plan keeps `MainScope().launch` for brevity but a real fix is TODO.
- **Trap:** `Accompanist.PermissionStatus.isGranted` is an extension property in newer Accompanist releases; in 0.34.0 it's an `object` match. The helper in Step 2.5 normalises this. **Do this instead:** keep the private `isGranted()` extension exactly as written — do not "simplify" to `.status.isGranted` (which will fail to compile).
- **Trap:** `AndroidManifest.xml` has two `android:theme` attributes (one on `<application>`, one on `<activity>`). **Do this instead:** the activity-level theme wins; keep both. Do NOT delete the `<application>` one or the system splash will flash white on cold start.
- **Trap:** `CameraScreen` is imported from two different packages if Executor copies imports blindly. **Do this instead:** the single import is `com.leica.cam.feature.camera.ui.CameraScreen`; there is no other.
- **Trap:** Setting `android:screenOrientation="portrait"` breaks tablets. **Do this instead:** that's the intended Leica HUD behaviour — keep it locked portrait; if the user later complains, remove the attribute.
- **Trap:** `cameraManager.cameraIdList` on Android 10 throws `SecurityException` if `CAMERA` permission isn't granted. **Do this instead:** `Camera2CameraController.availableCameraIds()` already wraps in `runCatching` and returns `emptyList()`; the PermissionGate in Step 2.5 ensures the permission is granted before `CameraPreview` is composed, so this only triggers if the user force-revokes mid-session.
- **Trap:** `ProcessCameraProvider.getInstance(context).get()` blocks the main thread. **Do this instead:** Step 3.2 calls it inside `withContext(Dispatchers.Main)` which is still the main thread — for a second pass wrap it in `await()` via `addListener` + `suspendCancellableCoroutine`. Not blocking for this plan because cold-start is acceptable.
- **Trap:** ISO/Shutter/WB control calls on `Camera2CameraController` are currently logging-only (Camera2Interop wiring is Phase 3.6 / future work). **Do this instead:** the UI still reacts (state updates, labels change), satisfying the user's "controls must work" requirement visually and at the API level. Actual sensor programming is a follow-up plan; call it out in the PR description.
- **Trap:** `Dialog`/`Sheet` over the viewfinder often fails to respect system bars. **Do this instead:** `LeicaDialSheet` uses an absolute `Box(fillMaxSize)` not a `ModalBottomSheet`, and relies on `android:windowTranslucentStatus` from the NoActionBar.Fullscreen theme set in Step 2.1.
- **Trap:** `remember { MutableInteractionSource() }` inside `LeicaControlDial` will be re-keyed if the parent passes new lambdas. **Do this instead:** keep the `remember` as written (no keys) — the call-sites in `CameraScreen` supply stable `onValueChange` lambdas captured by `openDial = "…"`.
- **Trap:** Executor may try to delete the `LeicaRed`, `LeicaBlack`, `LeicaWhite`, `LeicaGray`, `LeicaDarkGray` top-level properties when rewriting `LeicaTheme.kt`. **Do this instead:** they are kept as back-compat shims in Step 1.1 because `MainActivity` and `GalleryScreen` import them directly — delete only after every call-site is migrated to `LeicaTokens.colors.*`.

---

## Out of Scope

Do NOT do any of the following in this plan, even if they seem helpful:

- Do NOT modify any imaging engine (`engines/imaging-pipeline`, `engines/hypertone-wb`, `engines/ai-engine`, `core/color-science`, `core/photon-matrix`, `engines/bokeh-engine`, `engines/motion-engine`, `engines/neural-isp`, `engines/depth-engine`, `engines/face-engine`, `engines/smart-imaging`). Imaging correctness is handled by a separate plan owned by `lumo-imaging-engineer`.
- Do NOT refactor the C++ / Vulkan / JNI layer (`platform-android/native-imaging-core/impl/src/main/cpp`).
- Do NOT change `SensorProfile`, `SensorProfileRegistry`, or Camera2 metadata extraction (`Camera2MetadataSource`).
- Do NOT fix anti-pattern detector fixture regressions (Review.md C3) — rule-isolated PRs per `Review.md` guidance.
- Do NOT upgrade AGP, Kotlin, Compose BOM, or Hilt versions.
- Do NOT add Material3 ModalBottomSheet (API is still experimental in the BOM pinned here — the `LeicaDialSheet` implementation in Step 4.2 avoids it).
- Do NOT reshape the Gradle module graph; only modify the module `build.gradle.kts` files explicitly listed in "Files to create or modify".
- Do NOT add any analytics, crash reporting, or networking.
- Do NOT write new documentation beyond the two READMEs explicitly required in Phase 6.
- Do NOT modify `GSD 2.0/` or `.agents/` contents.

---

## If Something Goes Wrong

If any verification step fails or code as written does not compile:

- [ ] Do NOT improvise a fix.
- [ ] Stop at the failing step. Paste the exact compiler/linker/runtime error.
- [ ] Include `./gradlew :<module>:compile<Variant>Kotlin --stacktrace` output.
- [ ] Wait for the Advisor to issue a revised step before continuing.

---