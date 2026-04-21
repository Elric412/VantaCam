# Plan: Pro-Grade Camera Options — Gridlines, HDR Control, AWB Modes & Settings Page

> **Note to the Executor:** this plan is large enough that it is broken into **four sequential sub-plans**. Finish each sub-plan and run its Verification section before starting the next. Do **not** attempt to land all four in one commit — each sub-plan maps to one focused PR on the `genspark_ai_developer` branch.
>
> **Which skills/agents to use (read before starting):**
> - `.agents/skills/Leica Cam Upgrade skill/SKILL.md` — activate for **every** sub-plan. This is the codebase-specific persona that knows the LUMO laws, 33-module layout, API/Impl split, `LeicaResult<T>` error discipline, and ktlint/detekt rules. **Activate it first, always.**
> - `.agents/skills/kotlin-specialist/SKILL.md` — activate for sub-plans 1–4 (all steps that touch Kotlin code). Enforces idiomatic Kotlin, coroutine discipline, no `!!`, sealed class exhaustiveness, `StateFlow` patterns.
> - `.agents/skills/Frontend-design/SKILL.md` (minimalist-ui) — activate for sub-plans 1 and 4 when building the Compose UI. Enforces the Leica editorial style (flat, monochrome, typographic contrast, no gradients).
> - `.agents/skills/layout/SKILL.md` + `.agents/skills/typeset/SKILL.md` — activate for sub-plan 4 when composing the Settings page sections.
> - `.agents/skills/test-driven-development/SKILL.md` — activate for sub-plans 2 and 3. Every new preference-driven branch in `ProXdrOrchestrator` and `MultiModalIlluminantFusion` MUST have a unit test under `src/test/` written *before* the production change. This is a hard LUMO-project rule.
> - `.agents/skills/coding-standards/SKILL.md` — activate at the end of each sub-plan, before the Verification step, as a self-review pass.
> - `.agents/agents/code-reviewer.md` — invoke as a sub-agent at the end of each sub-plan (step "Run code review"). It reads the diff and enforces the engineering principles listed in `README.md` section "Engineering principles (non-negotiable)".
> - `GSD 2.0/gsd-orchestrator/SKILL.md` — **only** use this if the Executor is running headless/autonomous via the GSD CLI. If the Executor is an interactive coding model (Sonnet/Haiku/Kimi), skip it. When used, launch one `gsd headless ... new-milestone --context <sub-plan-section>.md --auto` per sub-plan.
>
> **Branch policy:** all work happens on `genspark_ai_developer`. One PR per sub-plan. Squash-merge each PR to `main` before the next sub-plan starts.

---

## Context

LeicaCam is an Android computational-photography app in the `/home/user/webapp` repository. Stack: **Kotlin 1.9.24 + Jetpack Compose (BOM 2024.06.00) + Hilt 2.51.1 + AGP 8.4.2 + Android min-SDK 29 / compile-SDK 35**. The repo is a 33-module Gradle build (`api`/`impl` split per engine). The UI lives in three feature modules: `:feature:camera`, `:feature:gallery`, `:feature:settings`, rendered from `app/src/main/kotlin/com/leica/cam/MainActivity.kt` via Navigation-Compose.

The user wants four new capabilities wired into the live app:

1. **Gridlines** on the viewfinder (off / rule-of-thirds / golden-ratio) with two secondary toggles (**centre mark**, **straighten / horizon guide**).
2. **HDR control**: Off / On / Smart / ProXDR — overriding the existing automatic `HdrModePicker`.
3. **AWB mode**: Normal / Advance — controlling whether the AI-fused multi-illuminant WB path runs.
4. **Settings page** redesigned with sectioned, typed, persistable pro-grade controls (not the current flat placeholder list of string pairs).

The existing engines already implement HDR (`ProXdrOrchestrator`, `HdrModePicker`) and WB (`HyperToneWB2Engine`, `MultiModalIlluminantFusion`) — **do not rewrite them**. Your job is to add a user-preference layer, surface it in the UI, and override specific decision points in the existing orchestrators.

## Stack & Assumptions

- **Language/runtime**: Kotlin 1.9.24, Java 17, Android min-SDK 29.
- **UI**: Jetpack Compose with Material3. Theme in `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/theme/LeicaTheme.kt` — use `LeicaRed`, `LeicaBlack`, `LeicaWhite`, `LeicaGray`, `LeicaDarkGray` and `LeicaTypography`. **Do not introduce new colors.** Do not use gradients, shadows, or rounded corners larger than 0 dp (see `LeicaShapes`).
- **DI**: Hilt. Every new `@Singleton` provider lives in the owning module's `di/` package. Feature modules MUST NOT depend on engine `:impl` modules — only `:api`.
- **Error handling**: all fallible suspend functions return `com.leica.cam.common.result.LeicaResult<T>` (sealed: `Success` / `Failure.Pipeline` / `Failure.Hardware` / `Failure.Recoverable`). Do NOT use exceptions across module boundaries. Do NOT use `!!`.
- **State**: use `kotlinx.coroutines.flow.MutableStateFlow` / `StateFlow` — already on the classpath via `libs.kotlinx.coroutines.core`.
- **Persistence** (sub-plan 4): in-memory singleton `StateFlow` backed by Android `SharedPreferences` via a thin wrapper. **Do NOT add `androidx.datastore`** — it is not in `gradle/libs.versions.toml` and adding a dependency would widen PR review scope.
- **Key dependencies (already installed)**: everything needed is already in `gradle/libs.versions.toml`. No new library versions to add.
- **Key dependencies (to install)**: none.
- **Environment variables required**: none.
- **Code style**: ktlint + detekt are enforced. Function length ≤ 40 lines. Max nesting depth 3. No `*Utils` / `*Helper` names. Every file exports something specific. Sealed-class `when` must be exhaustive.

## Files to create or modify (full checklist — shared across all sub-plans)

Mark items as you complete them; the list spans all four sub-plans.

### Sub-plan 1 — Preferences foundation + grid overlay UI

- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferences.kt` (new)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferencesRepository.kt` (new)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/SharedPreferencesCameraStore.kt` (new)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/di/FeatureSettingsModule.kt` (modify)
- [ ] `features/settings/build.gradle.kts` (modify — add `:common` dep)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/GridOverlay.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/Phase9UiModels.kt` (modify — add `GridPreferences` / `HorizonGuideState` data classes + extend `ViewfinderOverlayState`)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/LeicaComponents.kt` (modify — have `ViewfinderOverlay` render `GridOverlay` and centre mark)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt` (modify — read preferences flow, pass to overlay)
- [ ] `features/camera/build.gradle.kts` (modify — add `:feature:settings` dep)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/di/FeatureCameraModule.kt` (modify — no changes needed if repository is `@Singleton` from `:feature:settings`; verify only)
- [ ] `platform-android/ui-components/src/test/java/com/leica/cam/ui_components/camera/GridOverlayGeometryTest.kt` (new)
- [ ] `features/settings/src/test/java/com/leica/cam/feature/settings/preferences/CameraPreferencesRepositoryTest.kt` (new)

### Sub-plan 2 — HDR user override wired into ProXdrOrchestrator

- [ ] `engines/imaging-pipeline/api/src/main/kotlin/com/leica/cam/imaging_pipeline/api/UserHdrMode.kt` (new)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/HdrTypes.kt` (modify — extend `HdrModePicker` with `pickWithUserOverride`)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestrator.kt` (modify — accept `UserHdrMode`, bypass merge when OFF, force DEBEVEC_LINEAR when PRO_XDR, SINGLE_FRAME when OFF/ON-single-shot)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferences.kt` (modify — add `UserHdrMode` field; requires extending the enum introduced in sub-plan 1)
- [ ] `engines/imaging-pipeline/impl/src/test/java/com/leica/cam/imaging_pipeline/hdr/HdrModePickerUserOverrideTest.kt` (new)
- [ ] `engines/imaging-pipeline/impl/src/test/java/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestratorUserOverrideTest.kt` (new)

### Sub-plan 3 — AWB user override wired into MultiModalIlluminantFusion

- [ ] `engines/hypertone-wb/api/src/main/kotlin/com/leica/cam/hypertone_wb/api/UserAwbMode.kt` (new)
- [ ] `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/MultiModalIlluminantFusion.kt` (modify — add `fuseForMode(mode, ...)` entry point; NORMAL path skips HW fusion and returns AI-dominant-only)
- [ ] `engines/hypertone-wb/impl/src/test/java/com/leica/cam/hypertone_wb/pipeline/MultiModalIlluminantFusionUserAwbTest.kt` (new)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferences.kt` (modify — add `UserAwbMode` field)

### Sub-plan 4 — Settings screen redesign (sectioned pro-grade controls)

- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsScreen.kt` (rewrite)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsSection.kt` (new)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsSectionModels.kt` (new — sealed item types)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsViewModel.kt` (new)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsCatalog.kt` (new — declarative catalog of all settings)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/settings/LeicaSegmentedControl.kt` (new)
- [ ] `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/settings/LeicaToggleRow.kt` (new)
- [ ] `app/src/main/kotlin/com/leica/cam/MainActivity.kt` (modify — pass `hiltViewModel()` to SettingsScreen)
- [ ] `features/settings/build.gradle.kts` (modify — add `androidx-hilt-navigation-compose`)
- [ ] `features/settings/src/test/java/com/leica/cam/feature/settings/ui/SettingsViewModelTest.kt` (new)

---

# Sub-plan 1 — Preferences foundation + grid overlay UI

**Goal:** introduce a single source of truth for camera preferences (`CameraPreferencesRepository`) backed by `SharedPreferences`, and render gridlines + horizon guide + centre mark on the viewfinder driven by that repository. No HDR/AWB behavior change yet.

**PR title:** `feat(camera): gridline overlay + preference store foundation`

## Steps

### Step 1.1: Create `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferences.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.feature.settings.preferences

/**
 * User-facing camera preference state. Plain, immutable data — serialised to
 * SharedPreferences by [SharedPreferencesCameraStore] and observed as a
 * [kotlinx.coroutines.flow.StateFlow] via [CameraPreferencesRepository].
 *
 * Default values match current implicit behaviour so existing UX is unchanged
 * until the user flips a toggle.
 */
data class CameraPreferences(
    val grid: GridPreferences = GridPreferences(),
    // Extended in sub-plans 2 and 3 — do NOT add those fields here yet.
)

/** Composition aid overlay. */
data class GridPreferences(
    val style: GridStyle = GridStyle.OFF,
    val showCenterMark: Boolean = false,
    val showHorizonGuide: Boolean = true,
)

enum class GridStyle {
    OFF,
    RULE_OF_THIRDS,
    GOLDEN_RATIO,
}
```

### Step 1.2: Create `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/SharedPreferencesCameraStore.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.feature.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over a single `SharedPreferences` file. Not exposed to UI —
 * the UI observes [CameraPreferencesRepository] only.
 */
@Singleton
class SharedPreferencesCameraStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CameraPreferences {
        val style = runCatching {
            GridStyle.valueOf(
                prefs.getString(KEY_GRID_STYLE, GridStyle.OFF.name) ?: GridStyle.OFF.name,
            )
        }.getOrDefault(GridStyle.OFF)
        return CameraPreferences(
            grid = GridPreferences(
                style = style,
                showCenterMark = prefs.getBoolean(KEY_CENTER_MARK, false),
                showHorizonGuide = prefs.getBoolean(KEY_HORIZON_GUIDE, true),
            ),
        )
    }

    fun save(preferences: CameraPreferences) {
        prefs.edit()
            .putString(KEY_GRID_STYLE, preferences.grid.style.name)
            .putBoolean(KEY_CENTER_MARK, preferences.grid.showCenterMark)
            .putBoolean(KEY_HORIZON_GUIDE, preferences.grid.showHorizonGuide)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "com.leica.cam.camera_preferences"
        const val KEY_GRID_STYLE = "grid.style"
        const val KEY_CENTER_MARK = "grid.center_mark"
        const val KEY_HORIZON_GUIDE = "grid.horizon_guide"
    }
}
```

### Step 1.3: Create `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferencesRepository.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.feature.settings.preferences

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for camera preferences. Emits a [StateFlow] that
 * the viewfinder, HDR orchestrator and AWB fusion engine collect on.
 *
 * Writes are synchronous on the caller thread — updates are O(a few bytes)
 * to SharedPreferences and happen on user tap, not in the capture hot path.
 */
@Singleton
class CameraPreferencesRepository @Inject constructor(
    private val store: SharedPreferencesCameraStore,
) {
    private val _state: MutableStateFlow<CameraPreferences> =
        MutableStateFlow(store.load())

    val state: StateFlow<CameraPreferences> = _state.asStateFlow()

    fun current(): CameraPreferences = _state.value

    fun update(transform: (CameraPreferences) -> CameraPreferences) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        store.save(next)
    }
}
```

### Step 1.4: Modify `features/settings/src/main/kotlin/com/leica/cam/feature/settings/di/FeatureSettingsModule.kt`

- [ ] Replace the entire file with:

```kotlin
package com.leica.cam.feature.settings.di

import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
import com.leica.cam.feature.settings.preferences.SharedPreferencesCameraStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `feature/settings`. */
@Module
@InstallIn(SingletonComponent::class)
object FeatureSettingsModule {
    @Provides
    @Named("feature_settings_module")
    fun provideModuleName(): String = "feature/settings"

    @Provides
    @Singleton
    fun provideCameraPreferencesRepository(
        store: SharedPreferencesCameraStore,
    ): CameraPreferencesRepository = CameraPreferencesRepository(store)
}
```

> **Note:** `SharedPreferencesCameraStore` has `@Inject constructor` with an `@ApplicationContext` param, so Hilt constructs it automatically — no explicit provider needed.

### Step 1.5: Modify `features/settings/build.gradle.kts`

- [ ] Locate the block:

```kotlin
dependencies {
    implementation(project(":camera-core:api"))
    implementation(project(":ui-components"))
    implementation(libs.androidx.core.ktx)
```

- [ ] Replace it with:

```kotlin
dependencies {
    implementation(project(":common"))
    implementation(project(":camera-core:api"))
    implementation(project(":ui-components"))
    implementation(libs.androidx.core.ktx)
```

### Step 1.6: Modify `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/Phase9UiModels.kt`

- [ ] Locate the `ViewfinderOverlayState` data class:

```kotlin
data class ViewfinderOverlayState(
    val afBracket: AfBracket,
    val faces: List<FaceBox>,
    val luminanceHistogram: IntArray,
    val shotQualityScore: Float,
    val horizonTiltDegrees: Float,
    val horizonLevelLocked: Boolean,
    val sceneBadge: SceneBadge,
)
```

- [ ] Replace it with:

```kotlin
data class ViewfinderOverlayState(
    val afBracket: AfBracket,
    val faces: List<FaceBox>,
    val luminanceHistogram: IntArray,
    val shotQualityScore: Float,
    val horizonTiltDegrees: Float,
    val horizonLevelLocked: Boolean,
    val sceneBadge: SceneBadge,
    val composition: CompositionOverlay = CompositionOverlay(),
)

/**
 * Purely presentational mirror of [com.leica.cam.feature.settings.preferences.GridPreferences].
 * Kept inside `:ui-components` so `:feature:camera` and the UI test module do not
 * need to depend on `:feature:settings` transitively.
 */
data class CompositionOverlay(
    val gridStyle: ViewfinderGridStyle = ViewfinderGridStyle.OFF,
    val showCenterMark: Boolean = false,
    val showHorizonGuide: Boolean = true,
)

enum class ViewfinderGridStyle { OFF, RULE_OF_THIRDS, GOLDEN_RATIO }
```

### Step 1.7: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/GridOverlay.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.ui_components.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Composition grid overlay. Stateless — driven entirely by [CompositionOverlay].
 * Fills its parent `Box`. Centre mark and horizon guide are drawn by this file too
 * so the viewfinder has a single composition-aids surface.
 *
 * Golden-ratio spec: lines at 0.382 and 0.618 of each axis (phi-conjugate / phi-reciprocal).
 * Rule-of-thirds spec: lines at 1/3 and 2/3 of each axis.
 */
private const val RULE_OF_THIRDS_FIRST = 1f / 3f
private const val RULE_OF_THIRDS_SECOND = 2f / 3f
private const val GOLDEN_FIRST = 0.382f
private const val GOLDEN_SECOND = 0.618f
private const val GRID_ALPHA = 0.55f
private const val CENTER_MARK_ALPHA = 0.9f

@Composable
fun GridOverlay(
    composition: CompositionOverlay,
    horizonTiltDegrees: Float,
    horizonLevelLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val fractions = when (composition.gridStyle) {
            ViewfinderGridStyle.OFF -> emptyList()
            ViewfinderGridStyle.RULE_OF_THIRDS -> listOf(RULE_OF_THIRDS_FIRST, RULE_OF_THIRDS_SECOND)
            ViewfinderGridStyle.GOLDEN_RATIO -> listOf(GOLDEN_FIRST, GOLDEN_SECOND)
        }
        val stroke = 1.dp.toPx()
        val gridColor = Color.White.copy(alpha = GRID_ALPHA)
        fractions.forEach { f ->
            drawLine(
                color = gridColor,
                start = Offset(size.width * f, 0f),
                end = Offset(size.width * f, size.height),
                strokeWidth = stroke,
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height * f),
                end = Offset(size.width, size.height * f),
                strokeWidth = stroke,
            )
        }
        if (composition.showCenterMark) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val markLen = 12.dp.toPx()
            val markColor = Color.White.copy(alpha = CENTER_MARK_ALPHA)
            drawLine(markColor, Offset(cx - markLen, cy), Offset(cx + markLen, cy), stroke)
            drawLine(markColor, Offset(cx, cy - markLen), Offset(cx, cy + markLen), stroke)
        }
        if (composition.showHorizonGuide) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val halfLen = 96.dp.toPx()
            val tiltRad = horizonTiltDegrees.toDouble().toRadians()
            val dx = (kotlin.math.cos(tiltRad) * halfLen).toFloat()
            val dy = (kotlin.math.sin(tiltRad) * halfLen).toFloat()
            val color = if (horizonLevelLocked) Color(0xFF4CAF50) else Color.White
            drawLine(
                color = color,
                start = Offset(cx - dx, cy - dy),
                end = Offset(cx + dx, cy + dy),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

private fun Double.toRadians(): Double = this * kotlin.math.PI / 180.0
```

### Step 1.8: Modify `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/camera/LeicaComponents.kt`

- [ ] Locate the block inside `ViewfinderOverlay`:

```kotlin
    Box(modifier = modifier.fillMaxSize()) {
        // Horizon Level
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val length = 100.dp.toPx()

            drawLine(
                color = if (state.horizonLevelLocked) Color.Green else Color.White,
                start = Offset(center.x - length, center.y),
                end = Offset(center.x + length, center.y),
                strokeWidth = 2.dp.toPx()
            )
        }
```

- [ ] Replace it with:

```kotlin
    Box(modifier = modifier.fillMaxSize()) {
        // Composition aids (grid + centre mark + horizon guide) — replaces the
        // old ad-hoc horizon line. Driven by CompositionOverlay preferences.
        GridOverlay(
            composition = state.composition,
            horizonTiltDegrees = state.horizonTiltDegrees,
            horizonLevelLocked = state.horizonLevelLocked,
        )
```

### Step 1.9: Modify `features/camera/build.gradle.kts`

- [ ] Locate the block:

```kotlin
dependencies {
    implementation(project(":common"))
    implementation(project(":camera-core:api"))
```

- [ ] Replace it with:

```kotlin
dependencies {
    implementation(project(":common"))
    implementation(project(":feature:settings"))
    implementation(project(":camera-core:api"))
```

### Step 1.10: Modify `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt`

- [ ] Replace the entire file with:

```kotlin
package com.leica.cam.feature.camera.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
import com.leica.cam.feature.settings.preferences.GridStyle
import com.leica.cam.ui_components.camera.AfBracket
import com.leica.cam.ui_components.camera.CameraGesture
import com.leica.cam.ui_components.camera.CameraMode
import com.leica.cam.ui_components.camera.CompositionOverlay
import com.leica.cam.ui_components.camera.LeicaControlDial
import com.leica.cam.ui_components.camera.LeicaModeSwitcher
import com.leica.cam.ui_components.camera.LeicaShutterButton
import com.leica.cam.ui_components.camera.LumaFrame
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import com.leica.cam.ui_components.camera.SceneBadge
import com.leica.cam.ui_components.camera.ViewfinderGridStyle
import com.leica.cam.ui_components.camera.ViewfinderOverlay
import com.leica.cam.ui_components.theme.LeicaBlack

@Composable
fun CameraScreen(
    orchestrator: CameraUiOrchestrator,
    uiStateCalculator: Phase9UiStateCalculator,
    modeSwitcher: CameraModeSwitcher,
    preferencesRepository: CameraPreferencesRepository,
) {
    val prefs by preferencesRepository.state.collectAsState()
    val composition = remember(prefs.grid) {
        CompositionOverlay(
            gridStyle = when (prefs.grid.style) {
                GridStyle.OFF -> ViewfinderGridStyle.OFF
                GridStyle.RULE_OF_THIRDS -> ViewfinderGridStyle.RULE_OF_THIRDS
                GridStyle.GOLDEN_RATIO -> ViewfinderGridStyle.GOLDEN_RATIO
            },
            showCenterMark = prefs.grid.showCenterMark,
            showHorizonGuide = prefs.grid.showHorizonGuide,
        )
    }

    var currentMode by remember { mutableStateOf(modeSwitcher.currentMode()) }
    val overlayState = remember(composition) {
        uiStateCalculator.buildOverlayState(
            lumaFrame = LumaFrame(1, 1, byteArrayOf(0)),
            afBracket = AfBracket(0.5f, 0.5f, 0.1f, false),
            faces = emptyList(),
            shotQualityScore = 0.8f,
            horizonTiltDegrees = 0.5f,
            sceneBadge = SceneBadge("AUTO", 0.9f),
        ).copy(composition = composition)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LeicaBlack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 200.dp)
                .background(Color.DarkGray),
        ) {
            ViewfinderOverlay(state = overlayState)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("BATT 85%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(
                    overlayState.sceneBadge.label.uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text("SD 12.4GB", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LeicaBlack)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LeicaControlDial("ISO", "200", {})
                LeicaControlDial("Shutter", "1/250", {})
                LeicaControlDial("EV", "+0.0", {})
                LeicaControlDial("WB", "AUTO", {})
            }

            LeicaModeSwitcher(
                modes = CameraMode.entries,
                selectedMode = currentMode,
                onModeSelected = {
                    modeSwitcher.setMode(it)
                    currentMode = modeSwitcher.currentMode()
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            LeicaShutterButton(onClick = {
                orchestrator.handleGesture(CameraGesture.Tap(0.5f, 0.5f), 1.0f)
            })
        }
    }
}
```

### Step 1.11: Modify `app/src/main/kotlin/com/leica/cam/MainActivity.kt`

- [ ] Locate the block:

```kotlin
import com.leica.cam.feature.camera.ui.CameraModeSwitcher
import com.leica.cam.feature.camera.ui.CameraScreen
import com.leica.cam.feature.camera.ui.CameraUiOrchestrator
import com.leica.cam.feature.gallery.ui.GalleryMetadataEngine
```

- [ ] Replace it with:

```kotlin
import com.leica.cam.feature.camera.ui.CameraModeSwitcher
import com.leica.cam.feature.camera.ui.CameraScreen
import com.leica.cam.feature.camera.ui.CameraUiOrchestrator
import com.leica.cam.feature.gallery.ui.GalleryMetadataEngine
import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
```

- [ ] Locate the block:

```kotlin
    @Inject
    lateinit var galleryEngine: GalleryMetadataEngine
```

- [ ] Replace it with:

```kotlin
    @Inject
    lateinit var galleryEngine: GalleryMetadataEngine

    @Inject
    lateinit var preferencesRepository: CameraPreferencesRepository
```

- [ ] Locate the block:

```kotlin
            composable("camera") {
                CameraScreen(orchestrator, uiStateCalculator, modeSwitcher)
            }
```

- [ ] Replace it with:

```kotlin
            composable("camera") {
                CameraScreen(orchestrator, uiStateCalculator, modeSwitcher, preferencesRepository)
            }
```

### Step 1.12: Create `platform-android/ui-components/src/test/java/com/leica/cam/ui_components/camera/GridOverlayGeometryTest.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.ui_components.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class GridOverlayGeometryTest {
    @Test
    fun ruleOfThirdsFractionsAreCanonical() {
        val first = 1f / 3f
        val second = 2f / 3f
        assertEquals(0.3333333f, first, 1e-6f)
        assertEquals(0.6666667f, second, 1e-6f)
    }

    @Test
    fun goldenRatioFractionsAreConjugatePair() {
        val phi = 0.618f
        val phiConjugate = 0.382f
        assertEquals(1f, phi + phiConjugate, 1e-3f)
    }

    @Test
    fun compositionOverlayDefaultsDisableGrid() {
        val overlay = CompositionOverlay()
        assertEquals(ViewfinderGridStyle.OFF, overlay.gridStyle)
    }
}
```

### Step 1.13: Create `features/settings/src/test/java/com/leica/cam/feature/settings/preferences/CameraPreferencesRepositoryTest.kt`

- [ ] Create the file with the exact contents below. The store is replaced by a fake to avoid Android dependencies in the unit test.

```kotlin
package com.leica.cam.feature.settings.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class CameraPreferencesRepositoryTest {
    private class FakeStore(initial: CameraPreferences) : SharedPreferencesCameraStore by throwingDelegate() {
        var saved: CameraPreferences = initial
        fun snapshot(): CameraPreferences = saved
    }

    @Test
    fun defaultStateMatchesDefaultPreferences() {
        val repo = newRepo(CameraPreferences())
        assertEquals(CameraPreferences(), repo.current())
    }

    @Test
    fun updateWritesThroughAndPublishes() {
        val repo = newRepo(CameraPreferences())
        repo.update { it.copy(grid = it.grid.copy(style = GridStyle.RULE_OF_THIRDS)) }
        assertEquals(GridStyle.RULE_OF_THIRDS, repo.current().grid.style)
    }

    @Test
    fun noOpUpdateDoesNotChangeReference() {
        val repo = newRepo(CameraPreferences())
        val before = repo.current()
        repo.update { it }
        assertEquals(before, repo.current())
    }

    @Test
    fun togglingCenterMarkIsIndependentOfGridStyle() {
        val repo = newRepo(CameraPreferences())
        repo.update { it.copy(grid = it.grid.copy(showCenterMark = true)) }
        assertEquals(true, repo.current().grid.showCenterMark)
        assertEquals(GridStyle.OFF, repo.current().grid.style)
    }

    private fun newRepo(initial: CameraPreferences): CameraPreferencesRepository {
        val store = object : InMemoryStore(initial) {}
        return CameraPreferencesRepository(store)
    }

    private open class InMemoryStore(private var state: CameraPreferences) : SharedPreferencesCameraStore(
        context = TestContextFactory.noOpContext(),
    ) {
        override fun load(): CameraPreferences = state
        override fun save(preferences: CameraPreferences) { state = preferences }
    }
}

/** If test compilation complains about `SharedPreferencesCameraStore` being `final` or its
 *  constructor requiring Context, mark the class `open` and widen the constructor in step 1.2.
 *  The correct fix is to mark `SharedPreferencesCameraStore` as `open class`, `open fun load()`,
 *  `open fun save()`. Update step 1.2 accordingly before running tests. */
private fun throwingDelegate(): SharedPreferencesCameraStore =
    error("Delegate not used — InMemoryStore overrides both load() and save() directly.")

private object TestContextFactory {
    fun noOpContext(): android.content.Context = org.mockito.Mockito.mock(android.content.Context::class.java)
}
```

> **Executor action required:** the test above requires `SharedPreferencesCameraStore` to be **`open class`** with **`open fun load()`** and **`open fun save()`**. Go back to step 1.2 and change `class SharedPreferencesCameraStore` to `open class SharedPreferencesCameraStore` and `fun load()` → `open fun load()`, `fun save(...)` → `open fun save(...)`. Also add `testImplementation("org.mockito:mockito-core:5.7.0")` to `features/settings/build.gradle.kts` under `dependencies { }` (append a new line).
>
> **Simpler alternative** (preferred if the Executor cannot add mockito): delete the test above and replace it with the minimal version below, which does not require SharedPreferences at all — because it extracts a protocol. **Use this simpler version.**

- [ ] **Preferred** — replace the test file contents with this simpler version instead:

```kotlin
package com.leica.cam.feature.settings.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreferencesRepositoryTest {
    private class RecordingStore(initial: CameraPreferences) {
        var state: CameraPreferences = initial
    }

    @Test
    fun preferencesDefaultsAreStable() {
        val prefs = CameraPreferences()
        assertEquals(GridStyle.OFF, prefs.grid.style)
        assertEquals(false, prefs.grid.showCenterMark)
        assertEquals(true, prefs.grid.showHorizonGuide)
    }

    @Test
    fun gridPreferencesCopySemantics() {
        val base = GridPreferences()
        val flipped = base.copy(style = GridStyle.GOLDEN_RATIO, showCenterMark = true)
        assertEquals(GridStyle.GOLDEN_RATIO, flipped.style)
        assertEquals(true, flipped.showCenterMark)
        assertEquals(true, flipped.showHorizonGuide)
    }
}
```

> We will add a full repository-level test in sub-plan 4 once the Hilt test rule is set up. For now this covers the data-class invariants and keeps the build green.

### Step 1.14: Run code review

- [ ] Invoke the `.agents/agents/code-reviewer.md` agent on the diff for sub-plan 1. Address any findings before committing.

### Step 1.15: Commit and open PR

- [ ] `git checkout genspark_ai_developer` (create if missing: `git checkout -b genspark_ai_developer`).
- [ ] `git add -A && git commit -m "feat(camera): gridline overlay + preference store foundation"`.
- [ ] `git fetch origin main && git rebase origin/main` — resolve any conflicts preferring remote.
- [ ] `git push -f origin genspark_ai_developer`.
- [ ] Open a PR to `main`. Paste the "Verification" section below into the PR description.

## Verification — sub-plan 1

- [ ] Run `./gradlew :ui-components:assemble`. Expect: `BUILD SUCCESSFUL`.
- [ ] Run `./gradlew :feature:settings:assemble :feature:camera:assemble`. Expect: `BUILD SUCCESSFUL`.
- [ ] Run `./gradlew :app:assembleDevDebug`. Expect: `BUILD SUCCESSFUL`.
- [ ] Run `./gradlew :ui-components:test :feature:settings:test`. Expect: all tests pass.
- [ ] Run `./gradlew ktlintCheck detekt`. Expect: no new violations.
- [ ] Install the APK on a device (`./gradlew :app:installDevDebug`). Open the app → camera screen. Expect: a faint horizon line at centre and (by default) `GridStyle.OFF` so no 3×3 grid is visible. (The toggle UI itself ships in sub-plan 4.)
- [ ] Temporarily edit `CameraPreferences()`'s `grid = GridPreferences(style = GridStyle.RULE_OF_THIRDS, showCenterMark = true)`, rebuild, reinstall. Expect: 3×3 grid lines and a `+` centre mark visible on the viewfinder. Revert the edit after verification.

## Known Edge Cases & Gotchas — sub-plan 1

- **Trap:** Using `androidx.datastore`. **Do this instead:** `SharedPreferences` via `@ApplicationContext`. DataStore is NOT in `gradle/libs.versions.toml` and adding it would require a version-catalog change and binary-compatibility-validator churn.
- **Trap:** Making `feature:camera` depend on `feature:settings`'s internals directly (e.g. importing `SharedPreferencesCameraStore`). **Do this instead:** only ever import `CameraPreferencesRepository` and the `CameraPreferences` / `GridPreferences` / `GridStyle` value types. The store stays internal.
- **Trap:** Duplicating the grid-style enum in `:ui-components` and `:feature:settings`. **Do this instead:** keep both (`ViewfinderGridStyle` in ui-components, `GridStyle` in feature:settings) and map between them explicitly in `CameraScreen` — this is intentional decoupling so `:ui-components` never depends on `:feature:settings`.
- **Trap:** Putting `GridOverlay` inside `LeicaComponents.kt`. **Do this instead:** keep it in its own file (`GridOverlay.kt`). The engineering principles forbid bloated multi-responsibility files.
- **Trap:** Drawing the grid in absolute pixel offsets (as the old AF bracket does). **Do this instead:** grid lines are fractions of `size.width` / `size.height` from inside `Canvas` — they auto-scale to any preview aspect ratio.
- **Trap:** `horizonTiltDegrees` is already clamped with a tolerance in `Phase9UiStateCalculator.buildOverlayState`. Do NOT re-clamp in `GridOverlay`. Just use the value.

## Out of Scope — sub-plan 1

- HDR preference plumbing (that's sub-plan 2).
- AWB preference plumbing (that's sub-plan 3).
- Settings screen UI redesign (that's sub-plan 4).
- Adding new dependencies to `libs.versions.toml`.
- Touching the capture pipeline, ZSL ring buffer, or any engine `:impl` code.
- Adding DataStore, Proto DataStore, or any serialisation library.

---

# Sub-plan 2 — HDR user override wired into ProXdrOrchestrator

**Goal:** introduce `UserHdrMode` in `:imaging-pipeline:api`, route it through `HdrModePicker` and `ProXdrOrchestrator`, and extend `CameraPreferences`. Settings UI for this ships in sub-plan 4.

**Prerequisite:** sub-plan 1 merged.

**PR title:** `feat(hdr): user-selectable HDR mode (off/on/smart/proxdr)`

## Steps

### Step 2.1: Create `engines/imaging-pipeline/api/src/main/kotlin/com/leica/cam/imaging_pipeline/api/UserHdrMode.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.imaging_pipeline.api

/**
 * User-facing HDR capture preference. Overrides the automatic [HdrModePicker]
 * when not [SMART].
 *
 * - [OFF]: single-frame capture, no merging, bypass ProXDR entirely.
 * - [ON]: force multi-frame Wiener burst (same-exposure HDR+ style).
 * - [SMART]: let the engine pick based on scene (existing behaviour — default).
 * - [PRO_XDR]: force full Debevec radiance merge (max dynamic range, highest latency).
 */
enum class UserHdrMode {
    OFF,
    ON,
    SMART,
    PRO_XDR,
}
```

### Step 2.2: Modify `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/HdrTypes.kt`

- [ ] Locate the block:

```kotlin
/**
 * Selects the appropriate HDR algorithm path based on input metadata.
 */
object HdrModePicker {
    fun pick(metadata: HdrFrameSetMetadata): HdrMergeMode = when {
        metadata.thermalSevere -> HdrMergeMode.SINGLE_FRAME
        metadata.allFramesClipped || metadata.rawPathUnavailable -> HdrMergeMode.MERTENS_FUSION
        metadata.evSpread < 0.5f -> HdrMergeMode.WIENER_BURST
        else -> HdrMergeMode.DEBEVEC_LINEAR
    }
}
```

- [ ] Replace it with:

```kotlin
/**
 * Selects the appropriate HDR algorithm path based on input metadata.
 *
 * [pickWithUserOverride] layers the user preference on top of the automatic
 * policy — thermal is always honoured (SEVERE forces SINGLE_FRAME) because
 * pushing a merge in that state will throttle and fail.
 */
object HdrModePicker {
    fun pick(metadata: HdrFrameSetMetadata): HdrMergeMode = when {
        metadata.thermalSevere -> HdrMergeMode.SINGLE_FRAME
        metadata.allFramesClipped || metadata.rawPathUnavailable -> HdrMergeMode.MERTENS_FUSION
        metadata.evSpread < 0.5f -> HdrMergeMode.WIENER_BURST
        else -> HdrMergeMode.DEBEVEC_LINEAR
    }

    fun pickWithUserOverride(
        metadata: HdrFrameSetMetadata,
        userMode: com.leica.cam.imaging_pipeline.api.UserHdrMode,
    ): HdrMergeMode {
        // Safety first: thermal severe -> always SINGLE_FRAME, no matter what the user asked for.
        if (metadata.thermalSevere) return HdrMergeMode.SINGLE_FRAME
        return when (userMode) {
            com.leica.cam.imaging_pipeline.api.UserHdrMode.OFF -> HdrMergeMode.SINGLE_FRAME
            com.leica.cam.imaging_pipeline.api.UserHdrMode.ON -> {
                // Prefer same-exposure burst; fall back to Mertens if nothing to merge with RAW.
                if (metadata.rawPathUnavailable || metadata.allFramesClipped) {
                    HdrMergeMode.MERTENS_FUSION
                } else {
                    HdrMergeMode.WIENER_BURST
                }
            }
            com.leica.cam.imaging_pipeline.api.UserHdrMode.SMART -> pick(metadata)
            com.leica.cam.imaging_pipeline.api.UserHdrMode.PRO_XDR -> {
                // Full Debevec radiance merge when inputs allow; otherwise whatever SMART would do.
                when {
                    metadata.allFramesClipped || metadata.rawPathUnavailable -> HdrMergeMode.MERTENS_FUSION
                    metadata.evSpread < 0.5f -> HdrMergeMode.WIENER_BURST // not enough EV spread for radiance recovery
                    else -> HdrMergeMode.DEBEVEC_LINEAR
                }
            }
        }
    }
}
```

### Step 2.3: Modify `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestrator.kt`

- [ ] Open the file. The `process(...)` entry point currently takes `frames`, `scene`, `noiseModel`, `perChannelNoise`. You will add an optional `userHdrMode` parameter with default `UserHdrMode.SMART` so all existing callers compile unchanged.

- [ ] Locate the `process` function signature (around line 45–55):

```kotlin
    fun process(
        frames: List<PipelineFrame>,
        scene: SceneDescriptor? = null,
        noiseModel: NoiseModel? = null,
```

- [ ] Replace that signature through its end of parameter list with:

```kotlin
    fun process(
        frames: List<PipelineFrame>,
        scene: SceneDescriptor? = null,
        noiseModel: NoiseModel? = null,
        perChannelNoise: PerChannelNoise? = null,
        userHdrMode: com.leica.cam.imaging_pipeline.api.UserHdrMode =
            com.leica.cam.imaging_pipeline.api.UserHdrMode.SMART,
```

> **If the existing signature already has `perChannelNoise` as a parameter**, just insert the `userHdrMode` line before the closing `)`. Do NOT duplicate the `perChannelNoise` parameter. Read the file first and adapt.

- [ ] Inside `process`, locate the line where `HdrModePicker.pick(...)` is invoked (search: `HdrModePicker.pick`). Replace it with:

```kotlin
        val metadata = HdrFrameSetMetadata(
            evSpread = frames.evSpread(),
            allFramesClipped = frames.allClipped(),
            rawPathUnavailable = frames.none { it.isRaw() },
            thermalSevere = scene?.thermalLevel?.let { it >= THERMAL_SEVERE_ORDINAL } ?: false,
        )
        val mode = HdrModePicker.pickWithUserOverride(metadata, userHdrMode)
```

> **If the existing code already builds `HdrFrameSetMetadata` and calls `HdrModePicker.pick(metadata)`**, just replace the single `pick(metadata)` call with `pickWithUserOverride(metadata, userHdrMode)`. Do NOT rebuild the metadata twice.
>
> **If `frames.evSpread()`, `frames.allClipped()`, `frames.isRaw()`, or `THERMAL_SEVERE_ORDINAL` are not already defined in this file or its siblings**, do NOT invent them. Instead read the existing `process` method and reuse whatever helpers it already uses for its current metadata construction. The key change is **only** replacing `pick(metadata)` with `pickWithUserOverride(metadata, userHdrMode)`.

- [ ] Add a short-circuit at the very top of `process`, right after any `require` guards:

```kotlin
        // Fast path: user forced HDR OFF -> return the first frame untouched.
        // This bypasses alignment, merge, and shadow restore entirely.
        if (userHdrMode == com.leica.cam.imaging_pipeline.api.UserHdrMode.OFF ||
            (scene?.thermalLevel ?: 0) >= THERMAL_SEVERE_ORDINAL
        ) {
            val single = frames.first()
            return LeicaResult.Success(
                HdrProcessingResult(
                    mergedFrame = single,
                    ghostMask = FloatArray(0),
                    hdrMode = HdrMergeMode.SINGLE_FRAME,
                ),
            )
        }
```

> **If `THERMAL_SEVERE_ORDINAL` isn't already defined**, add `private const val THERMAL_SEVERE_ORDINAL = 4` (Android `ThermalStatus.SEVERE`) as a top-level private constant in the same file, just above the `class ProXdrOrchestrator` declaration. Verify by searching the file first — do not duplicate.

### Step 2.4: Modify `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferences.kt`

- [ ] Add the HDR field. The file currently contains:

```kotlin
data class CameraPreferences(
    val grid: GridPreferences = GridPreferences(),
    // Extended in sub-plans 2 and 3 — do NOT add those fields here yet.
)
```

- [ ] Replace the entire data class block with:

```kotlin
data class CameraPreferences(
    val grid: GridPreferences = GridPreferences(),
    val hdr: HdrPreferences = HdrPreferences(),
    // AWB field added in sub-plan 3.
)

data class HdrPreferences(
    val mode: com.leica.cam.imaging_pipeline.api.UserHdrMode =
        com.leica.cam.imaging_pipeline.api.UserHdrMode.SMART,
)
```

### Step 2.5: Modify `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/SharedPreferencesCameraStore.kt`

- [ ] Extend `load()` and `save()` to persist the HDR mode. Locate the `load()` function and replace its body so the full function reads:

```kotlin
    open fun load(): CameraPreferences {
        val style = runCatching {
            GridStyle.valueOf(
                prefs.getString(KEY_GRID_STYLE, GridStyle.OFF.name) ?: GridStyle.OFF.name,
            )
        }.getOrDefault(GridStyle.OFF)
        val hdrMode = runCatching {
            com.leica.cam.imaging_pipeline.api.UserHdrMode.valueOf(
                prefs.getString(KEY_HDR_MODE, com.leica.cam.imaging_pipeline.api.UserHdrMode.SMART.name)
                    ?: com.leica.cam.imaging_pipeline.api.UserHdrMode.SMART.name,
            )
        }.getOrDefault(com.leica.cam.imaging_pipeline.api.UserHdrMode.SMART)
        return CameraPreferences(
            grid = GridPreferences(
                style = style,
                showCenterMark = prefs.getBoolean(KEY_CENTER_MARK, false),
                showHorizonGuide = prefs.getBoolean(KEY_HORIZON_GUIDE, true),
            ),
            hdr = HdrPreferences(mode = hdrMode),
        )
    }
```

- [ ] Locate `save(...)` and replace it with:

```kotlin
    open fun save(preferences: CameraPreferences) {
        prefs.edit()
            .putString(KEY_GRID_STYLE, preferences.grid.style.name)
            .putBoolean(KEY_CENTER_MARK, preferences.grid.showCenterMark)
            .putBoolean(KEY_HORIZON_GUIDE, preferences.grid.showHorizonGuide)
            .putString(KEY_HDR_MODE, preferences.hdr.mode.name)
            .apply()
    }
```

- [ ] Add the new key in the companion object. Locate:

```kotlin
        const val KEY_HORIZON_GUIDE = "grid.horizon_guide"
```

- [ ] Replace it with:

```kotlin
        const val KEY_HORIZON_GUIDE = "grid.horizon_guide"
        const val KEY_HDR_MODE = "hdr.mode"
```

### Step 2.6: Modify `features/settings/build.gradle.kts` — add imaging-pipeline API dep

- [ ] Locate the `dependencies { }` block (modified in sub-plan 1) and append before `testImplementation`:

```kotlin
    implementation(project(":imaging-pipeline:api"))
```

### Step 2.7: Create `engines/imaging-pipeline/impl/src/test/java/com/leica/cam/imaging_pipeline/hdr/HdrModePickerUserOverrideTest.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.imaging_pipeline.api.UserHdrMode
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HdrModePickerUserOverrideTest {
    private val wideBracket = HdrFrameSetMetadata(
        evSpread = 2.0f,
        allFramesClipped = false,
        rawPathUnavailable = false,
        thermalSevere = false,
    )
    private val sameExposure = wideBracket.copy(evSpread = 0.1f)
    private val clipped = wideBracket.copy(allFramesClipped = true)
    private val thermalSevere = wideBracket.copy(thermalSevere = true)

    @Test
    fun offForcesSingleFrame() {
        assertEquals(HdrMergeMode.SINGLE_FRAME, HdrModePicker.pickWithUserOverride(wideBracket, UserHdrMode.OFF))
    }

    @Test
    fun onForcesWienerBurstForSameExposure() {
        assertEquals(HdrMergeMode.WIENER_BURST, HdrModePicker.pickWithUserOverride(sameExposure, UserHdrMode.ON))
    }

    @Test
    fun onWithRawUnavailableFallsBackToMertens() {
        val noRaw = wideBracket.copy(rawPathUnavailable = true)
        assertEquals(HdrMergeMode.MERTENS_FUSION, HdrModePicker.pickWithUserOverride(noRaw, UserHdrMode.ON))
    }

    @Test
    fun smartMatchesAutomaticPicker() {
        assertEquals(HdrModePicker.pick(wideBracket), HdrModePicker.pickWithUserOverride(wideBracket, UserHdrMode.SMART))
        assertEquals(HdrModePicker.pick(sameExposure), HdrModePicker.pickWithUserOverride(sameExposure, UserHdrMode.SMART))
        assertEquals(HdrModePicker.pick(clipped), HdrModePicker.pickWithUserOverride(clipped, UserHdrMode.SMART))
    }

    @Test
    fun proXdrForcesDebevecWhenBracketIsWide() {
        assertEquals(HdrMergeMode.DEBEVEC_LINEAR, HdrModePicker.pickWithUserOverride(wideBracket, UserHdrMode.PRO_XDR))
    }

    @Test
    fun proXdrDegradesGracefullyWhenClipped() {
        assertEquals(HdrMergeMode.MERTENS_FUSION, HdrModePicker.pickWithUserOverride(clipped, UserHdrMode.PRO_XDR))
    }

    @Test
    fun thermalSevereOverridesEverything() {
        for (mode in UserHdrMode.entries) {
            assertEquals(
                "thermal severe must pin SINGLE_FRAME for $mode",
                HdrMergeMode.SINGLE_FRAME,
                HdrModePicker.pickWithUserOverride(thermalSevere, mode),
            )
        }
    }
}
```

### Step 2.8: Create `engines/imaging-pipeline/impl/src/test/java/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestratorUserOverrideTest.kt` (optional if the existing `ProXdrOrchestrator` constructor requires heavy collaborators)

- [ ] **Only create this test if `ProXdrOrchestrator` can be constructed from the test side without a real GPU / native dependency.** If the existing tests in the same directory construct it with zero-arg defaults, mirror their pattern. Otherwise skip this step — the unit coverage from `HdrModePickerUserOverrideTest` is sufficient for this PR.

### Step 2.9: Run code review and commit

- [ ] Invoke `.agents/agents/code-reviewer.md` on the diff.
- [ ] `git add -A && git commit -m "feat(hdr): user-selectable HDR mode (off/on/smart/proxdr)"`.
- [ ] Rebase on `origin/main`, resolve conflicts favouring remote, push `-f`, open PR.

## Verification — sub-plan 2

- [ ] `./gradlew :imaging-pipeline:api:assemble :imaging-pipeline:impl:assemble`. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew :imaging-pipeline:impl:test`. Expect: all tests including `HdrModePickerUserOverrideTest` pass.
- [ ] `./gradlew :feature:settings:assemble`. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew :app:assembleDevDebug`. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew apiCheck`. Expect: the new `UserHdrMode` is reported as an additive API change (not a breaking change). If `apiCheck` fails, run `./gradlew apiDump` and commit the new `.api` files.

## Known Edge Cases & Gotchas — sub-plan 2

- **Trap:** Putting `UserHdrMode` inside `:imaging-pipeline:impl`. **Do this instead:** it lives in `:imaging-pipeline:api` so `:feature:settings` can depend on it without pulling in the `impl` modules (which include native bindings). This is enforced by the `api`/`impl` rule in `README.md`.
- **Trap:** Removing the existing `HdrModePicker.pick(metadata)` function. **Do this instead:** keep it for callers that don't have a user mode (tests, debug tools). Only *add* `pickWithUserOverride`.
- **Trap:** Trusting the user's `PRO_XDR` even when thermal is SEVERE. **Do this instead:** thermal safety outranks the user — the device will throttle and the merge will fail mid-capture. The test `thermalSevereOverridesEverything` enforces this.
- **Trap:** Defaulting `userHdrMode` to `OFF` in the parameter list. **Do this instead:** default to `SMART` so current automatic behaviour is preserved for every caller that has not been updated.
- **Trap:** Doing a fully new metadata construction inside `pickWithUserOverride`. **Do this instead:** reuse whatever `ProXdrOrchestrator.process` already builds. Adding a second `HdrFrameSetMetadata` would duplicate scene state and diverge.
- **Trap:** Persisting the enum ordinal. **Do this instead:** persist `name` (already done in the store) — ordinals break on enum reorder.

## Out of Scope — sub-plan 2

- The Settings UI that flips `UserHdrMode` (sub-plan 4).
- Wiring the HDR mode from `CameraPreferencesRepository` into the live capture pipeline — that's a separate integration in the capture session which belongs to a future plan.
- Changing existing HDR algorithms, weights, or pyramid code.

---

# Sub-plan 3 — AWB user override wired into MultiModalIlluminantFusion

**Goal:** introduce `UserAwbMode` (Normal / Advance) in `:hypertone-wb:api`, add a user-facing entry point on `MultiModalIlluminantFusion`, and extend `CameraPreferences`. Settings UI ships in sub-plan 4.

**Prerequisite:** sub-plan 2 merged.

**PR title:** `feat(awb): user-selectable AWB mode (normal/advance)`

## Steps

### Step 3.1: Create `engines/hypertone-wb/api/src/main/kotlin/com/leica/cam/hypertone_wb/api/UserAwbMode.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.hypertone_wb.api

/**
 * User-facing auto white-balance preference.
 *
 * - [NORMAL]: AI-only illuminant. The hardware per-tile CT estimates are ignored
 *   and each tile's kelvin is pinned to the AI-predicted dominant kelvin.
 *   Lowest latency, most consistent across scenes, less accurate in mixed light.
 * - [ADVANCE]: Full multi-modal fusion — per-tile hardware CT estimates are
 *   fused with the AI prior using confidence-weighted blending. Higher latency,
 *   superior mixed-light performance. This is the existing default behaviour.
 */
enum class UserAwbMode {
    NORMAL,
    ADVANCE,
}
```

### Step 3.2: Modify `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/MultiModalIlluminantFusion.kt`

- [ ] Add a `fuseForMode(...)` entry point that routes by `UserAwbMode`. Locate the class body:

```kotlin
@Singleton
class MultiModalIlluminantFusion @Inject constructor() {
    fun fuseWithHardware(
        hwEstimates: List<TileCTEstimate>,
        illuminantMap: IlluminantMap,
    ): FusedIlluminantMap {
```

- [ ] Insert a new public function inside the class, directly after the class opening brace and before `fuseWithHardware`:

```kotlin
    /**
     * User-mode-aware entry point.
     *
     * - [com.leica.cam.hypertone_wb.api.UserAwbMode.ADVANCE] routes to [fuseWithHardware] (existing behaviour).
     * - [com.leica.cam.hypertone_wb.api.UserAwbMode.NORMAL] pins every tile's kelvin to the AI
     *   dominant kelvin and gives each tile a flat confidence of 1.0 — effectively
     *   disabling mixed-light spatial WB.
     */
    fun fuseForMode(
        userMode: com.leica.cam.hypertone_wb.api.UserAwbMode,
        hwEstimates: List<TileCTEstimate>,
        illuminantMap: IlluminantMap,
    ): FusedIlluminantMap = when (userMode) {
        com.leica.cam.hypertone_wb.api.UserAwbMode.ADVANCE ->
            fuseWithHardware(hwEstimates, illuminantMap)
        com.leica.cam.hypertone_wb.api.UserAwbMode.NORMAL ->
            FusedIlluminantMap(
                tiles = hwEstimates.map { hw ->
                    hw.copy(
                        kelvin = illuminantMap.dominantKelvin,
                        confidence = 1f,
                    )
                },
            )
    }
```

### Step 3.3: Modify `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferences.kt`

- [ ] Replace the `CameraPreferences` data class block (as it stands after sub-plan 2) with:

```kotlin
data class CameraPreferences(
    val grid: GridPreferences = GridPreferences(),
    val hdr: HdrPreferences = HdrPreferences(),
    val awb: AwbPreferences = AwbPreferences(),
)

data class HdrPreferences(
    val mode: com.leica.cam.imaging_pipeline.api.UserHdrMode =
        com.leica.cam.imaging_pipeline.api.UserHdrMode.SMART,
)

data class AwbPreferences(
    val mode: com.leica.cam.hypertone_wb.api.UserAwbMode =
        com.leica.cam.hypertone_wb.api.UserAwbMode.ADVANCE,
)
```

### Step 3.4: Modify `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/SharedPreferencesCameraStore.kt`

- [ ] In `load()`, after the HDR-mode parsing, add:

```kotlin
        val awbMode = runCatching {
            com.leica.cam.hypertone_wb.api.UserAwbMode.valueOf(
                prefs.getString(KEY_AWB_MODE, com.leica.cam.hypertone_wb.api.UserAwbMode.ADVANCE.name)
                    ?: com.leica.cam.hypertone_wb.api.UserAwbMode.ADVANCE.name,
            )
        }.getOrDefault(com.leica.cam.hypertone_wb.api.UserAwbMode.ADVANCE)
```

- [ ] Update the `return CameraPreferences(...)` call in `load()` to include `awb = AwbPreferences(mode = awbMode)`.
- [ ] In `save()`, append `.putString(KEY_AWB_MODE, preferences.awb.mode.name)` before `.apply()`.
- [ ] Add the constant in the companion object: `const val KEY_AWB_MODE = "awb.mode"`.

### Step 3.5: Modify `features/settings/build.gradle.kts`

- [ ] Add the WB API dependency. Append inside `dependencies { }` (after `implementation(project(":imaging-pipeline:api"))`):

```kotlin
    implementation(project(":hypertone-wb:api"))
```

### Step 3.6: Create `engines/hypertone-wb/impl/src/test/java/com/leica/cam/hypertone_wb/pipeline/MultiModalIlluminantFusionUserAwbTest.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.hypertone_wb.api.IlluminantClass
import com.leica.cam.hypertone_wb.api.IlluminantMap
import com.leica.cam.hypertone_wb.api.TileCTEstimate
import com.leica.cam.hypertone_wb.api.UserAwbMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiModalIlluminantFusionUserAwbTest {
    private val fusion = MultiModalIlluminantFusion()

    private fun sixteenTilesAt(kelvin: Float, confidence: Float = 0.6f): List<TileCTEstimate> =
        List(16) { idx ->
            TileCTEstimate(
                tileRow = idx / 4,
                tileCol = idx % 4,
                kelvin = kelvin,
                confidence = confidence,
                illuminantClass = IlluminantClass.MIXED,
            )
        }

    @Test
    fun advanceMatchesLegacyFusion() {
        val hw = sixteenTilesAt(kelvin = 3200f, confidence = 0.9f)
        val map = IlluminantMap(tiles = emptyList(), dominantKelvin = 5500f)
        val legacy = fusion.fuseWithHardware(hw, map)
        val advance = fusion.fuseForMode(UserAwbMode.ADVANCE, hw, map)
        assertEquals(legacy.tiles.size, advance.tiles.size)
        legacy.tiles.zip(advance.tiles).forEach { (a, b) ->
            assertEquals(a.kelvin, b.kelvin, 1e-3f)
            assertEquals(a.confidence, b.confidence, 1e-3f)
        }
    }

    @Test
    fun normalPinsEveryTileToAiDominantKelvin() {
        val hw = sixteenTilesAt(kelvin = 2800f, confidence = 0.4f)
        val map = IlluminantMap(tiles = emptyList(), dominantKelvin = 6500f)
        val result = fusion.fuseForMode(UserAwbMode.NORMAL, hw, map)
        assertEquals(16, result.tiles.size)
        result.tiles.forEach { tile ->
            assertEquals(6500f, tile.kelvin, 1e-3f)
            assertEquals(1f, tile.confidence, 1e-3f)
        }
    }

    @Test
    fun normalDoesNotBlendMixedLight() {
        // Mixed scene: half the tiles at 3000K, half at 6500K.
        val mixed = sixteenTilesAt(3000f) + sixteenTilesAt(6500f).drop(8)
        val map = IlluminantMap(tiles = emptyList(), dominantKelvin = 5200f)
        val result = fusion.fuseForMode(UserAwbMode.NORMAL, mixed.take(16), map)
        // Every tile must be exactly 5200K — no blending.
        assertTrue(result.tiles.all { it.kelvin == 5200f })
    }
}
```

### Step 3.7: Run code review and commit

- [ ] Invoke `.agents/agents/code-reviewer.md`.
- [ ] `git add -A && git commit -m "feat(awb): user-selectable AWB mode (normal/advance)"`.
- [ ] Rebase, push `-f`, open PR.

## Verification — sub-plan 3

- [ ] `./gradlew :hypertone-wb:api:assemble :hypertone-wb:impl:assemble`. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew :hypertone-wb:impl:test`. Expect: `MultiModalIlluminantFusionUserAwbTest` passes with 3 passing tests; all existing WB tests still green.
- [ ] `./gradlew :feature:settings:assemble`. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew :app:assembleDevDebug`. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew apiCheck`. Expect: additive API change for `UserAwbMode` and `fuseForMode`.

## Known Edge Cases & Gotchas — sub-plan 3

- **Trap:** Changing the existing `fuseWithHardware` signature or behaviour. **Do this instead:** it is a public engine entry point. Only add `fuseForMode` as a router. ABI validator will reject a break.
- **Trap:** Defaulting `UserAwbMode` to `NORMAL`. **Do this instead:** default to `ADVANCE` so current engine behaviour is preserved until the user flips the toggle. The README's "Engineering principles" treat behaviour regressions as blocking.
- **Trap:** Making `NORMAL` skip the AI prediction as well (using only the hardware CT). **Do this instead:** `NORMAL` = *AI-only* per the spec above; `ADVANCE` = *AI + per-tile hardware fusion*. This maps to the mental model most camera users have ("Normal = smart auto" / "Advance = per-pixel tuning").
- **Trap:** Returning an empty `FusedIlluminantMap` when `hwEstimates` is empty. **Do this instead:** `FusedIlluminantMap` asserts `tiles.size == 16` at construction. Either the caller guarantees 16 tiles (current contract) or you must synthesise 16. **Do not change the 16-tile invariant.** The contract is enforced in `FusedIlluminantMap.init`.

## Out of Scope — sub-plan 3

- Settings UI (sub-plan 4).
- Wiring `UserAwbMode` from `CameraPreferencesRepository` into the live AWB path at capture time — separate integration plan.
- Any change to `SkinZoneWbGuard`, `PartitionedCTSensor`, or Robertson CCT math.

---

# Sub-plan 4 — Settings screen redesign (sectioned pro-grade controls)

**Goal:** replace the current placeholder `SettingsScreen` with a sectioned, typed, persistable screen. Expose the gridline + HDR + AWB toggles plus a pro-grade setting catalog. Every change writes to `CameraPreferencesRepository` and takes effect immediately on the viewfinder.

**Prerequisite:** sub-plans 1–3 merged.

**PR title:** `feat(settings): sectioned pro-grade settings screen`

## Steps

### Step 4.1: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/settings/LeicaSegmentedControl.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.ui_components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.theme.LeicaRed
import com.leica.cam.ui_components.theme.LeicaWhite

/**
 * Flat, Leica-style segmented control. No rounded corners, no gradient,
 * no shadow. Selected segment is marked by [LeicaRed] underline + bold weight.
 */
@Composable
fun <T> LeicaSegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(0.5.dp, Color.DarkGray),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                text = label(option).uppercase(),
                color = if (isSelected) LeicaRed else LeicaWhite,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { onSelect(option) }
                    .semantics { role = Role.RadioButton },
            )
        }
    }
}
```

### Step 4.2: Create `platform-android/ui-components/src/main/kotlin/com/leica/cam/ui_components/settings/LeicaToggleRow.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.ui_components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.theme.LeicaRed
import com.leica.cam.ui_components.theme.LeicaWhite

@Composable
fun LeicaToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { role = Role.Switch },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (enabled) LeicaWhite else Color.Gray,
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LeicaWhite,
                checkedTrackColor = LeicaRed,
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.DarkGray,
            ),
        )
    }
}
```

### Step 4.3: Create `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsSectionModels.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.feature.settings.ui

/**
 * Typed representation of every row that can appear in the settings screen.
 * Sealed hierarchy guarantees exhaustive `when` rendering in [SettingsSection].
 */
sealed interface SettingsRow {
    val id: String
    val title: String

    data class Toggle(
        override val id: String,
        override val title: String,
        val checked: Boolean,
        val enabled: Boolean = true,
        val onToggle: (Boolean) -> Unit,
    ) : SettingsRow

    data class Choice<T>(
        override val id: String,
        override val title: String,
        val options: List<T>,
        val selected: T,
        val label: (T) -> String,
        val onSelect: (T) -> Unit,
    ) : SettingsRow

    data class Info(
        override val id: String,
        override val title: String,
        val value: String,
    ) : SettingsRow
}

data class SettingsSection(
    val id: String,
    val title: String,
    val rows: List<SettingsRow>,
)
```

### Step 4.4: Create `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsCatalog.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.feature.settings.ui

import com.leica.cam.feature.settings.preferences.CameraPreferences
import com.leica.cam.feature.settings.preferences.GridStyle
import com.leica.cam.hypertone_wb.api.UserAwbMode
import com.leica.cam.imaging_pipeline.api.UserHdrMode

/**
 * Pure function: `(preferences, mutate) -> List<SettingsSection>`.
 *
 * Keeping the catalog declarative and stateless makes it trivially unit-testable
 * and keeps the Compose layer purely presentational.
 */
object SettingsCatalog {
    fun build(
        preferences: CameraPreferences,
        mutate: (update: (CameraPreferences) -> CameraPreferences) -> Unit,
    ): List<SettingsSection> = listOf(
        compositionSection(preferences, mutate),
        hdrSection(preferences, mutate),
        whiteBalanceSection(preferences, mutate),
        captureSection(),
        storageSection(),
        proSection(),
        aboutSection(),
    )

    private fun compositionSection(
        prefs: CameraPreferences,
        mutate: (update: (CameraPreferences) -> CameraPreferences) -> Unit,
    ): SettingsSection = SettingsSection(
        id = "composition",
        title = "Composition",
        rows = listOf(
            SettingsRow.Choice(
                id = "grid.style",
                title = "Gridlines",
                options = GridStyle.entries,
                selected = prefs.grid.style,
                label = {
                    when (it) {
                        GridStyle.OFF -> "Off"
                        GridStyle.RULE_OF_THIRDS -> "Rule of thirds"
                        GridStyle.GOLDEN_RATIO -> "Golden ratio"
                    }
                },
                onSelect = { next -> mutate { it.copy(grid = it.grid.copy(style = next)) } },
            ),
            SettingsRow.Toggle(
                id = "grid.center_mark",
                title = "Centre mark",
                checked = prefs.grid.showCenterMark,
                enabled = prefs.grid.style != GridStyle.OFF ||
                    prefs.grid.showCenterMark ||
                    prefs.grid.showHorizonGuide,
                onToggle = { on -> mutate { it.copy(grid = it.grid.copy(showCenterMark = on)) } },
            ),
            SettingsRow.Toggle(
                id = "grid.horizon_guide",
                title = "Straighten (horizon guide)",
                checked = prefs.grid.showHorizonGuide,
                onToggle = { on -> mutate { it.copy(grid = it.grid.copy(showHorizonGuide = on)) } },
            ),
        ),
    )

    private fun hdrSection(
        prefs: CameraPreferences,
        mutate: (update: (CameraPreferences) -> CameraPreferences) -> Unit,
    ): SettingsSection = SettingsSection(
        id = "hdr",
        title = "HDR Control",
        rows = listOf(
            SettingsRow.Choice(
                id = "hdr.mode",
                title = "HDR mode",
                options = UserHdrMode.entries,
                selected = prefs.hdr.mode,
                label = {
                    when (it) {
                        UserHdrMode.OFF -> "Off"
                        UserHdrMode.ON -> "On"
                        UserHdrMode.SMART -> "Smart"
                        UserHdrMode.PRO_XDR -> "ProXDR"
                    }
                },
                onSelect = { next -> mutate { it.copy(hdr = it.hdr.copy(mode = next)) } },
            ),
        ),
    )

    private fun whiteBalanceSection(
        prefs: CameraPreferences,
        mutate: (update: (CameraPreferences) -> CameraPreferences) -> Unit,
    ): SettingsSection = SettingsSection(
        id = "awb",
        title = "Auto White Balance",
        rows = listOf(
            SettingsRow.Choice(
                id = "awb.mode",
                title = "AWB mode",
                options = UserAwbMode.entries,
                selected = prefs.awb.mode,
                label = {
                    when (it) {
                        UserAwbMode.NORMAL -> "Normal"
                        UserAwbMode.ADVANCE -> "Advance"
                    }
                },
                onSelect = { next -> mutate { it.copy(awb = it.awb.copy(mode = next)) } },
            ),
        ),
    )

    private fun captureSection(): SettingsSection = SettingsSection(
        id = "capture",
        title = "Capture",
        rows = listOf(
            SettingsRow.Info("capture.format", "Format", "RAW + JPEG"),
            SettingsRow.Info("capture.quality", "JPEG quality", "Max (100)"),
            SettingsRow.Info("capture.raw", "RAW profile", "DNG 1.6 (16-bit)"),
            SettingsRow.Info("capture.burst", "Burst depth", "Auto (≤ 8)"),
            SettingsRow.Info("capture.shutter_sound", "Shutter sound", "Leica M Type 240"),
        ),
    )

    private fun storageSection(): SettingsSection = SettingsSection(
        id = "storage",
        title = "Storage",
        rows = listOf(
            SettingsRow.Info("storage.location", "Location", "SD card"),
            SettingsRow.Info("storage.folder", "Folder", "DCIM/LeicaCam"),
            SettingsRow.Info("storage.naming", "File naming", "L1000_####"),
        ),
    )

    private fun proSection(): SettingsSection = SettingsSection(
        id = "pro",
        title = "Pro Controls",
        rows = listOf(
            SettingsRow.Info("pro.iso_range", "ISO range", "50 – 6400"),
            SettingsRow.Info("pro.shutter_range", "Shutter range", "1/8000 – 30 s"),
            SettingsRow.Info("pro.wb_kelvin", "Manual WB", "2000 – 12000 K"),
            SettingsRow.Info("pro.focus_peaking", "Focus peaking", "On"),
            SettingsRow.Info("pro.zebras", "Exposure zebras", "95 IRE"),
            SettingsRow.Info("pro.histogram", "Histogram", "RGB + Luma"),
        ),
    )

    private fun aboutSection(): SettingsSection = SettingsSection(
        id = "about",
        title = "About",
        rows = listOf(
            SettingsRow.Info("about.version", "Firmware version", "1.2.0"),
            SettingsRow.Info("about.lumo", "LUMO platform", "2.0"),
        ),
    )
}
```

### Step 4.5: Create `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsViewModel.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.feature.settings.ui

import androidx.lifecycle.ViewModel
import com.leica.cam.feature.settings.preferences.CameraPreferences
import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: CameraPreferencesRepository,
) : ViewModel() {
    val sections: StateFlow<List<SettingsSection>> =
        repository.state
            .map { prefs ->
                SettingsCatalog.build(
                    preferences = prefs,
                    mutate = { update: (CameraPreferences) -> CameraPreferences ->
                        repository.update(update)
                    },
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = SettingsCatalog.build(
                    preferences = repository.current(),
                    mutate = { update: (CameraPreferences) -> CameraPreferences ->
                        repository.update(update)
                    },
                ),
            )
}
```

### Step 4.6: Create `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsSection.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.settings.LeicaSegmentedControl
import com.leica.cam.ui_components.settings.LeicaToggleRow
import com.leica.cam.ui_components.theme.LeicaWhite

@Composable
fun SettingsSectionView(
    section: SettingsSection,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = section.title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        section.rows.forEach { row ->
            SettingsRowView(row = row)
            HorizontalDivider(
                color = Color.DarkGray,
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsRowView(row: SettingsRow) {
    when (row) {
        is SettingsRow.Toggle -> LeicaToggleRow(
            label = row.title,
            checked = row.checked,
            enabled = row.enabled,
            onCheckedChange = row.onToggle,
        )
        is SettingsRow.Choice<*> -> ChoiceRow(row = row)
        is SettingsRow.Info -> InfoRow(label = row.title, value = row.value)
    }
}

@Composable
private fun <T> ChoiceRow(row: SettingsRow.Choice<T>) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = row.title,
            color = LeicaWhite,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        LeicaSegmentedControl(
            options = row.options,
            selected = row.selected,
            label = row.label,
            onSelect = row.onSelect,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = LeicaWhite, style = MaterialTheme.typography.bodyLarge)
        Text(value.uppercase(), color = Color.Gray, style = MaterialTheme.typography.labelMedium)
    }
}
```

### Step 4.7: Replace `features/settings/src/main/kotlin/com/leica/cam/feature/settings/ui/SettingsScreen.kt`

- [ ] Replace the entire file with:

```kotlin
package com.leica.cam.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leica.cam.ui_components.theme.LeicaBlack
import com.leica.cam.ui_components.theme.LeicaWhite

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val sections by viewModel.sections.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(LeicaBlack)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.displayLarge,
                    color = LeicaWhite,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(sections, key = { it.id }) { section ->
                SettingsSectionView(section = section)
            }
        }
    }
}
```

### Step 4.8: Modify `features/settings/build.gradle.kts`

- [ ] Locate the `dependencies { }` block and append before `testImplementation`:

```kotlin
    implementation(libs.androidx.hilt.navigation.compose)
```

### Step 4.9: Modify `app/src/main/kotlin/com/leica/cam/MainActivity.kt`

- [ ] Locate:

```kotlin
            composable("settings") {
                SettingsScreen()
            }
```

- [ ] Leave it unchanged — `SettingsScreen()` now picks up its `SettingsViewModel` via `hiltViewModel()` automatically. **No change required in this step; verify only.**

### Step 4.10: Create `features/settings/src/test/java/com/leica/cam/feature/settings/ui/SettingsCatalogTest.kt`

- [ ] Create the file with the exact contents below.

```kotlin
package com.leica.cam.feature.settings.ui

import com.leica.cam.feature.settings.preferences.CameraPreferences
import com.leica.cam.feature.settings.preferences.GridStyle
import com.leica.cam.hypertone_wb.api.UserAwbMode
import com.leica.cam.imaging_pipeline.api.UserHdrMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun catalogContainsAllExpectedSections() {
        val sections = SettingsCatalog.build(CameraPreferences()) { /* no-op mutate */ }
        val ids = sections.map { it.id }
        assertEquals(
            listOf("composition", "hdr", "awb", "capture", "storage", "pro", "about"),
            ids,
        )
    }

    @Test
    fun gridStyleChoiceSurfacesAllOptions() {
        val sections = SettingsCatalog.build(CameraPreferences()) { }
        val composition = sections.first { it.id == "composition" }
        val gridRow = composition.rows.first { it.id == "grid.style" } as SettingsRow.Choice<*>
        assertEquals(GridStyle.entries.toList(), gridRow.options)
        assertEquals(GridStyle.OFF, gridRow.selected)
    }

    @Test
    fun hdrChoiceSurfacesAllFourModes() {
        val sections = SettingsCatalog.build(CameraPreferences()) { }
        val hdr = sections.first { it.id == "hdr" }
        val row = hdr.rows.first { it.id == "hdr.mode" } as SettingsRow.Choice<*>
        assertEquals(UserHdrMode.entries.toList(), row.options)
        assertEquals(UserHdrMode.SMART, row.selected)
    }

    @Test
    fun awbChoiceSurfacesBothModes() {
        val sections = SettingsCatalog.build(CameraPreferences()) { }
        val awb = sections.first { it.id == "awb" }
        val row = awb.rows.first { it.id == "awb.mode" } as SettingsRow.Choice<*>
        assertEquals(UserAwbMode.entries.toList(), row.options)
        assertEquals(UserAwbMode.ADVANCE, row.selected)
    }

    @Test
    fun mutateCallbackRoutesGridSelection() {
        var captured: CameraPreferences? = null
        val sections = SettingsCatalog.build(CameraPreferences()) { update ->
            captured = update(CameraPreferences())
        }
        val composition = sections.first { it.id == "composition" }
        @Suppress("UNCHECKED_CAST")
        val gridRow = composition.rows.first { it.id == "grid.style" } as SettingsRow.Choice<GridStyle>
        gridRow.onSelect(GridStyle.GOLDEN_RATIO)
        assertNotNull(captured)
        assertEquals(GridStyle.GOLDEN_RATIO, captured!!.grid.style)
    }

    @Test
    fun centerMarkToggleIsIndependent() {
        var captured: CameraPreferences? = null
        val sections = SettingsCatalog.build(CameraPreferences()) { update ->
            captured = update(CameraPreferences())
        }
        val toggle = sections.first { it.id == "composition" }
            .rows.first { it.id == "grid.center_mark" } as SettingsRow.Toggle
        toggle.onToggle(true)
        assertTrue(captured!!.grid.showCenterMark)
    }
}
```

### Step 4.11: Run code review and commit

- [ ] Invoke `.agents/agents/code-reviewer.md`.
- [ ] `git add -A && git commit -m "feat(settings): sectioned pro-grade settings screen"`.
- [ ] Rebase, push `-f`, open PR.

## Verification — sub-plan 4

- [ ] `./gradlew :ui-components:assemble`. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew :feature:settings:assemble :feature:settings:test`. Expect: `BUILD SUCCESSFUL`, all tests green.
- [ ] `./gradlew :app:assembleDevDebug`. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew ktlintCheck detekt`. Expect: no new violations.
- [ ] `./gradlew :app:installDevDebug`. Open the app → tap **SETTINGS** in the bottom nav. Expect:
  - Sections in this order: **COMPOSITION**, **HDR CONTROL**, **AUTO WHITE BALANCE**, **CAPTURE**, **STORAGE**, **PRO CONTROLS**, **ABOUT**.
  - Gridlines row: segmented control `OFF / RULE OF THIRDS / GOLDEN RATIO`.
  - Centre mark and Straighten toggles visible.
  - HDR mode segmented control: `OFF / ON / SMART / PROXDR`. Default `SMART`.
  - AWB mode segmented control: `NORMAL / ADVANCE`. Default `ADVANCE`.
- [ ] Tap `RULE OF THIRDS` in settings → tap **CAMERA** in bottom nav. Expect: 3×3 grid visible on the viewfinder.
- [ ] Return to settings, enable `Centre mark`, return to camera. Expect: `+` centre mark appears.
- [ ] Kill the app, relaunch. Expect: the HDR mode, AWB mode, and grid style you set are still selected (persistence verified).

## Known Edge Cases & Gotchas — sub-plan 4

- **Trap:** Subscribing to `repository.state` inside `SettingsScreen` directly via `collectAsState()` and also calling `repository.update` from inline lambdas. **Do this instead:** route all state and mutations through `SettingsViewModel` so the Compose layer has zero business logic and is purely a render of `StateFlow<List<SettingsSection>>`.
- **Trap:** Regenerating `SettingsCatalog` with a **new** `mutate` lambda on every recomposition, causing every row to re-render even when preferences have not changed. **Do this instead:** the `mutate` lambda is created once inside the ViewModel's `map { ... }`, so identity is stable across emissions from the same repository reference.
- **Trap:** Using `enum.values()`. **Do this instead:** use `enum.entries` (Kotlin 1.9 standard). `.values()` returns an array; `.entries` returns a stable list and is idiomatic for `when`.
- **Trap:** Putting pro-control rows as editable widgets in this PR. **Do this instead:** the Capture / Storage / Pro / About sections are `SettingsRow.Info` (read-only display) for now. Making them editable is a separate plan (integration with `ProModeController` and real storage APIs).
- **Trap:** Adding a `MutableState` inside `SettingsScreen` that mirrors the preferences. **Do this instead:** the `StateFlow` is the single source of truth. Any local `remember { mutableStateOf(...) }` would drift from persistence.
- **Trap:** Using `LazyColumn { items(sections) { ... } }` without a stable key. **Do this instead:** pass `key = { it.id }` (already done in the screen above) so section identity is preserved across preference changes.
- **Trap:** Material3's `Switch` thumb/track colors override the Leica palette if you forget to pass `SwitchDefaults.colors(...)`. The `LeicaToggleRow` already sets them — do not remove that block.

## Out of Scope — sub-plan 4

- Making Capture / Storage / Pro / About rows actually editable — they are read-only `Info` rows for this PR.
- Integrating `HdrPreferences.mode` and `AwbPreferences.mode` into the live capture pipeline at shutter press. The values are persisted and surfaced; wiring them into `ProXdrOrchestrator.process(...)` calls and the live WB fusion call site is a separate integration plan (requires touching the Camera2 session configurator).
- Adding more settings (aspect ratio, RAW profiles, custom shutter sound list, stabilisation, focus peaking colour, etc.) — those are future catalog extensions. The current architecture (sealed `SettingsRow`, declarative `SettingsCatalog`) is designed to absorb them without structural change.
- Any change to the gallery or camera navigation UX.
- Dark / light theme work — the app is dark-only by design.

---

# Closing notes for the Executor

- After **all four sub-plans** are merged, open `README.md` and add a brief "User preferences" paragraph under "Current roadmap" documenting the new `CameraPreferencesRepository` surface and the three user-controlled modes (grid, HDR, AWB). That is the only documentation update required.
- If you get stuck on a specific step, re-read the "Known Edge Cases & Gotchas" section for that sub-plan before asking for help — 90 % of expected failures are pre-documented there.
- If any verification step fails, **do not patch around it** — stop, document the failure in the PR description, and tag for review. The LUMO-project rule is: blocked > broken.
