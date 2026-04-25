# Android Camera App — Pre-Release Checklist (50 Points)

## 🔴 CRITICAL — Must Pass Before Release

### Build & Signing
- [ ] Release build compiles without errors: `./gradlew assembleRelease`
- [ ] AAB built successfully: `./gradlew bundleRelease`
- [ ] App signed with release keystore (NOT debug key)
- [ ] Keystore stored securely (NOT in version control)
- [ ] `keystore.properties` in `.gitignore`
- [ ] minSdk, targetSdk, compileSdk are correct (target latest stable)
- [ ] versionCode incremented from previous release
- [ ] versionName updated (semantic: MAJOR.MINOR.PATCH)

### Permissions
- [ ] Only required permissions declared in AndroidManifest.xml
- [ ] Runtime permissions requested at point of use (not on launch)
- [ ] Permission denial handled gracefully (no crash)
- [ ] `android:exported` set on all Activities/Services/Receivers
- [ ] CAMERA permission requested before any camera operation
- [ ] Storage permissions are version-aware (READ_MEDIA_IMAGES on API 33+)

### Camera Core
- [ ] Camera opens successfully on front AND back
- [ ] Camera releases on app background (no "camera in use" error for other apps)
- [ ] Permission denial: no crash, show rationale
- [ ] Rotation handled: preview not distorted after rotation
- [ ] App resumes camera correctly after home → foreground
- [ ] Camera works on LEGACY hardware level devices
- [ ] `imageCapture.close()` / `image.close()` always called

### Storage
- [ ] Photos save to correct location (DCIM/Camera or custom album)
- [ ] Saved photos appear in system gallery
- [ ] No `FileNotFoundException` on API 29+ (no legacy paths)
- [ ] IS_PENDING flag set/cleared correctly for large files
- [ ] DNG/RAW files saved with correct MIME type

---

## 🟡 IMPORTANT — Should Pass Before Release

### ProGuard / R8
- [ ] Release build tested on physical device (not just emulator)
- [ ] No `ClassNotFoundException` in release build
- [ ] Camera classes not stripped (CameraX, Camera2 keep rules in place)
- [ ] Hilt/Dagger injection works in release
- [ ] Room database works in release
- [ ] Mapping file backed up: `build/outputs/mapping/release/mapping.txt`

### Performance
- [ ] App launch time < 3 seconds on mid-range device (cold start)
- [ ] Preview starts within 1.5 seconds of permission grant
- [ ] Photo capture latency < 500ms (shutter lag)
- [ ] Memory usage stable during extended use (no steady OOM growth)
- [ ] No UI jank: preview maintains 30fps minimum
- [ ] Large photos don't cause OOM (no unnecessary Bitmap allocations)

### Error Handling
- [ ] All try-catch blocks present around camera operations
- [ ] Camera errors shown to user (not silent fails)
- [ ] Graceful fallback if device doesn't support RAW/HDR
- [ ] Network errors handled (if app uses cloud features)
- [ ] Disk full handled: show user-friendly message

### UI / UX
- [ ] App works in both portrait and landscape
- [ ] UI doesn't overlap with camera cutout / punch-hole
- [ ] Shutter button not accessible before camera is ready
- [ ] Loading indicator shown during camera init
- [ ] Back navigation works correctly throughout the app

---

## 🟢 RECOMMENDED — Polish Before Release

### Code Quality
- [ ] No hardcoded strings (all in strings.xml)
- [ ] No `TODO` or `FIXME` comments in production paths
- [ ] No sensitive data logged (no keys, tokens, user data in Logcat)
- [ ] Log.d calls disabled in release (use Timber with release tree that no-ops)
- [ ] No deprecated APIs from target SDK level

### Testing
- [ ] Tested on minimum SDK device (API 24)
- [ ] Tested on latest Android version
- [ ] Tested on small screen (phone) AND large screen (tablet if applicable)
- [ ] Tested with camera permission denied
- [ ] Tested with storage full (>95% full)
- [ ] Tested with low battery / battery saver mode

### Play Store Requirements
- [ ] App icon provided (512×512 PNG, no transparency)
- [ ] Feature graphic provided (1024×500 PNG)
- [ ] At least 2 screenshots per device type
- [ ] Short description (≤80 characters)
- [ ] Full description (≤4000 characters)
- [ ] Privacy policy URL provided (required for camera permission)
- [ ] Content rating questionnaire completed
- [ ] Target audience declared

---

## Build Commands Quick Reference

```bash
# Debug build
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease

# Release AAB (for Play Store)
./gradlew bundleRelease

# Run all tests
./gradlew test
./gradlew connectedAndroidTest

# Lint check
./gradlew lint

# Clean
./gradlew clean

# Dependency tree
./gradlew app:dependencies --configuration releaseRuntimeClasspath

# Install release on connected device for testing
adb install -r app/build/outputs/apk/release/app-release.apk

# View crash logs
adb logcat --pid=$(adb shell pidof com.yourapp.package) -v time

# Check APK signature
apksigner verify --verbose app-release.apk

# Analyze APK size
./gradlew assembleRelease
# Then: Build → Analyze APK in Android Studio
```

---

## Privacy Policy (Required for Camera Apps on Play Store)

Camera apps MUST have a privacy policy that discloses:
- What camera data is collected (photos, metadata)
- Where it's stored (device-local, cloud, shared with 3rd parties)
- How it's used
- How users can delete their data

Template topics to cover:
1. Camera access: used only for [purpose], not transmitted externally
2. Storage: photos saved to device gallery only
3. Location data: if geotagging is enabled, explain
4. Third-party SDKs: list any analytics, ads, or cloud services
5. Data retention: how long data is kept
6. Contact information for privacy questions
