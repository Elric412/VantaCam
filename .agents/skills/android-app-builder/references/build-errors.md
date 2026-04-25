# Build Errors Reference — Complete Fix Catalog

## Gradle / AGP Errors

### "Could not find com.android.tools.build:gradle:X.X.X"
```kotlin
// Root cause: Wrong Gradle plugin version or missing repo
// Fix in project-level build.gradle.kts:
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
// Also verify settings.gradle.kts has google() repo
```

### "Gradle sync failed: Unable to resolve AGP version"
```kotlin
// Upgrade Gradle wrapper
// gradle/wrapper/gradle-wrapper.properties:
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-bin.zip
```

### "Incompatible plugins: kotlin-android vs kotlin-jvm"
```kotlin
// Only use one of these per module — never both
// For Android app modules: always use kotlin-android
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") // ← correct
    // id("org.jetbrains.kotlin.jvm") // ← WRONG for Android app
}
```

### "AAPT: error: resource not found"
```
Root cause: Reference to a resource that doesn't exist
Fixes:
1. Check for typos in @drawable/, @string/, @layout/ references
2. Invalidate Caches: File → Invalidate Caches / Restart
3. Run: ./gradlew clean assembleDebug
4. Make sure the resource file is in the correct directory
```

### "Execution failed for task ':app:mergeDebugResources'"
```
Root cause: Duplicate resource names across modules
Fix:
1. Run: ./gradlew app:mergeDebugResources --stacktrace
2. Look for "Duplicate resources" in output
3. Rename the conflicting resource files
4. Or add resource prefixes in library modules:
   android { resourcePrefix = "lib_" }
```

### "Manifest merger failed"
```xml
<!-- Common cause: activity exported status -->
<!-- Fix: Add explicit android:exported to all activities -->
<activity
    android:name=".MainActivity"
    android:exported="true">  <!-- REQUIRED for API 31+ -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
</activity>

<!-- If conflict from library: override in app manifest -->
<activity
    android:name="com.library.SomeActivity"
    android:exported="false"
    tools:replace="android:exported" />
```

### "Duplicate class kotlin.collections.jdk8"
```kotlin
// Kotlin stdlib version conflict
// Fix in app/build.gradle.kts:
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.0")
    }
}
```

### "class file for android.support.v4.* not found"
```kotlin
// Using old support library with AndroidX — can't mix them
// Fix: Migrate to AndroidX
// Add to gradle.properties:
android.useAndroidX=true
android.enableJetifier=true
// Then replace:
// import android.support.v4.app.ActivityCompat
// with:
// import androidx.core.app.ActivityCompat
```

### "Failed to resolve: androidx.camera:camera-core:X"
```kotlin
// Fix: Make sure you have the right version in libs.versions.toml
[versions]
camerax = "1.4.1"  // Check latest at developer.android.com/jetpack/androidx/releases/camera

[libraries]
camerax-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
// CRITICAL: Also add camera-camera2 — CameraX REQUIRES this at runtime even if not directly referenced
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
```

### "minCompileSdk (34) specified in a dependency's AAR metadata"
```kotlin
// Fix: Bump compileSdk to match the demanding dependency
android {
    compileSdk = 35  // bump from 33/34
    defaultConfig {
        targetSdk = 35
    }
}
```

### "Multidex" / "Cannot fit requested classes in a single dex file"
```kotlin
android {
    defaultConfig {
        multiDexEnabled = true
    }
}
dependencies {
    implementation("androidx.multidex:multidex:2.0.1")
}
// If minSdk >= 21, multidex is automatic — just add multiDexEnabled = true
```

### "Configuration 'compile' is obsolete"
```kotlin
// Replace old configurations:
// compile → implementation
// testCompile → testImplementation
// androidTestCompile → androidTestImplementation
// provided → compileOnly
// apk → runtimeOnly
```

### "Kapt error / annotation processing failed"
```kotlin
// For Hilt / Room annotation processors
plugins {
    id("kotlin-kapt")   // old approach
    // OR use KSP (faster):
    id("com.google.devtools.ksp")
}
dependencies {
    // Kapt:
    kapt("com.google.dagger:hilt-android-compiler:2.52")
    // KSP (preferred):
    ksp("com.google.dagger:hilt-android-compiler:2.52")
}
// Note: Don't mix kapt and ksp for the same processor
```

### "Build output path too long" (Windows)
```
Fix: Move project to C:\projects\ (short path)
Or enable long paths in Windows:
Computer\HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\FileSystem
LongPathsEnabled = 1
```

### "R8: TypeNotPresentException"
```proguard
# Fix: Add missing keep rule for the class R8 stripped
# Run build with: ./gradlew assembleRelease --info 2>&1 | grep "R8:"
# Then add:
-keep class com.yourpackage.missing.ClassName { *; }
```

### "Unresolved reference: BuildConfig"
```kotlin
// Fix: Enable BuildConfig feature generation
android {
    buildFeatures {
        buildConfig = true  // disabled by default in AGP 8+
    }
}
```

---

## Kotlin / Compile Errors

### "Suspend function should be called only from a coroutine"
```kotlin
// Fix: Launch from a CoroutineScope
viewModelScope.launch {
    val result = suspendFunctionCall()
}
// Or use runBlocking only in tests — never in production code
```

### "Smart cast to X is impossible because it's a mutable property"
```kotlin
// Fix: Copy to local val before use
val capturedValue = mutableProperty  // local copy
if (capturedValue != null) {
    capturedValue.doSomething()  // smart cast works now
}
```

### "Type mismatch: inferred type is Flow<X> but StateFlow<X> was expected"
```kotlin
// Fix: Convert Flow to StateFlow in ViewModel
val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
// Or use stateIn():
val uiState: StateFlow<CameraUiState> = repository.getState()
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CameraUiState()
    )
```

---

## Dependency Conflict Resolution

### Pattern: Resolve version conflict for any library
```kotlin
// In app/build.gradle.kts — force a specific version
configurations.all {
    resolutionStrategy {
        force("group:artifact:version")
        // Example:
        force("com.google.guava:guava:32.1.3-android")
    }
}
```

### Pattern: Exclude a transitive dependency
```kotlin
implementation("some.library:name:1.0") {
    exclude(group = "conflicting.group", module = "conflicting-module")
}
```

### Pattern: Debug dependency tree
```bash
# See full dependency tree
./gradlew app:dependencies --configuration debugRuntimeClasspath

# See why a specific dependency was included
./gradlew app:dependencyInsight \
  --dependency androidx.camera:camera-core \
  --configuration debugRuntimeClasspath
```

---

## Android Studio IDE Issues

### "Unresolved reference" even though code is correct
```
Fix sequence:
1. File → Sync Project with Gradle Files
2. File → Invalidate Caches → Invalidate and Restart
3. Build → Clean Project
4. Build → Rebuild Project
5. Check if the import is actually in build.gradle dependencies
```

### "Process 'command '/path/to/java'' finished with non-zero exit value 1"
```
Fix: Check full error in Build output (not just the summary)
Usually means:
- Compilation error in your code (look for red lines)
- OOM: increase JVM heap in gradle.properties:
  org.gradle.jvmargs=-Xmx4096m
- Missing SDK: install via SDK Manager
```

### "Android Studio can't find Android SDK"
```
Fix:
1. File → Project Structure → SDK Location
2. Point to your Android SDK directory
3. On macOS: ~/Library/Android/sdk
4. On Windows: C:\Users\Username\AppData\Local\Android\Sdk
5. On Linux: ~/Android/Sdk
```
