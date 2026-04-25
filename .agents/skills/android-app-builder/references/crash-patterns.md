# Crash Patterns — Android Camera App

## Camera Crashes

### CameraAccessException: CAMERA_DISABLED
```
Stack: android.hardware.camera2.CameraAccessException: CAMERA_DISABLED
Cause: Camera is disabled by device policy, or app opened camera from background
Fix:
1. Always check permissions before openCamera()
2. Never open camera from background service — must be foreground (Activity/Fragment/Service with foreground notification)
3. Check enterprise MDM policy on device
```
```kotlin
// Correct pre-check:
fun openCameraSafely(cameraId: String) {
    val perm = ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
    if (perm != PackageManager.PERMISSION_GRANTED) {
        // Don't crash — request permission
        return
    }
    try {
        cameraManager.openCamera(cameraId, stateCallback, handler)
    } catch (e: CameraAccessException) {
        when (e.reason) {
            CameraAccessException.CAMERA_DISABLED -> Log.e(TAG, "Camera disabled by policy")
            CameraAccessException.CAMERA_IN_USE -> Log.e(TAG, "Camera already in use")
            CameraAccessException.CAMERA_DISCONNECTED -> Log.e(TAG, "Camera disconnected")
            else -> Log.e(TAG, "Camera error: ${e.reason}", e)
        }
    }
}
```

### IllegalStateException: CameraX is not configured properly
```
Full message: CameraX is not configured properly. The most likely cause is you did not 
include a default implementation in your build such as 'camera-camera2'.

Fix: Add camera-camera2 to your dependencies — CameraX requires this at runtime
even if you don't call Camera2 APIs directly.
```
```kotlin
// build.gradle.kts — all of these are required:
dependencies {
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)     // ← REQUIRED even if not using Camera2 directly
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // Optional: extensions for Night/HDR/Bokeh
    implementation(libs.camerax.extensions)
}
```

### SecurityException: Permission Denial CAMERA
```
java.lang.SecurityException: Permission Denial: starting Intent { act=android.media.action.IMAGE_CAPTURE }
Cause: CAMERA permission not in manifest, or not granted at runtime
```
```kotlin
// Always check AND request:
private fun checkCameraPermission(): Boolean {
    return if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) {
        true
    } else {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION)
        false
    }
}

// Handle the result:
override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQUEST_CAMERA_PERMISSION) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            showPermissionDeniedMessage()
        }
    }
}
```

### IllegalStateException: Surface is not ready for writing
```
Cause: Trying to use camera before preview surface is initialized
Fix: Always set SurfaceProvider BEFORE binding use cases
```
```kotlin
// ❌ Wrong order:
cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
preview.setSurfaceProvider(previewView.surfaceProvider)  // TOO LATE

// ✅ Correct order:
val preview = Preview.Builder().build()
preview.setSurfaceProvider(previewView.surfaceProvider)  // Set first
cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
```

### OutOfMemoryError on image capture
```
Cause: Large bitmaps not recycled; ImageProxy not closed
Fix: Never convert large camera images to Bitmap without sampling; always close ImageProxy
```
```kotlin
// ❌ Memory leak:
imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
    override fun onCaptureSuccess(image: ImageProxy) {
        val bitmap = image.toBitmap() // Huge allocation
        // forgot: image.close() → LEAK
    }
})

// ✅ Always close:
imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
    override fun onCaptureSuccess(image: ImageProxy) {
        try {
            processImage(image)
        } finally {
            image.close() // Always in finally block
        }
    }
})

// ✅ Use OutputFileOptions to avoid Bitmap entirely for saves:
imageCapture.takePicture(outputFileOptions, executor, object : ImageCapture.OnImageSavedCallback {
    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
        // File saved directly — no Bitmap created
    }
    override fun onError(exception: ImageCaptureException) { }
})
```

### NullPointerException: imageCapture is null
```
Cause: takePicture called before camera is bound, or after unbindAll()
```
```kotlin
// Fix: Gate all camera operations behind null check
fun capturePhoto() {
    val imageCapture = this.imageCapture ?: run {
        Log.e(TAG, "ImageCapture not initialized. Was camera bound?")
        showError("Camera not ready")
        return
    }
    // proceed safely
}
```

### Preview distorted / rotated / black screen after rotation
```
Cause: Activity recreated without proper config change handling
Fix Option 1: Handle config changes yourself (best for camera apps)
```
```xml
<!-- AndroidManifest.xml -->
<activity
    android:name=".MainActivity"
    android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
```
```kotlin
// In Compose, PreviewView handles rotation automatically via:
PreviewView(
    modifier = Modifier.fillMaxSize(),
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE // or PERFORMANCE
)
// COMPATIBLE mode handles more device quirks; use PERFORMANCE for speed
```

### CameraUnavailableException: Camera already in use
```
Cause: Another camera session still active (from previous binding or another app)
```
```kotlin
// Fix: Always unbindAll before rebinding
val cameraProvider = ProcessCameraProvider.getInstance(context).await()
cameraProvider.unbindAll() // ← Critical
camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
```

---

## Storage & File Crashes

### FileNotFoundException: /sdcard/DCIM/...
```
Cause: Using legacy file paths on Android 10+ with scoped storage
Fix: Use MediaStore API exclusively (see ImageSaver in SKILL.md)
```

### MediaStore insert returns null
```kotlin
// Cause: MediaStore is not available (emulator issue) or WRITE permission denied
// Fix:
val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
if (uri == null) {
    Log.e(TAG, "MediaStore.insert returned null — check WRITE_EXTERNAL_STORAGE on API<29")
    // Fallback: save to app-specific storage
    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photo.jpg")
    file.outputStream().use { it.write(jpegBytes) }
    return file.toUri()
}
```

### "READ_EXTERNAL_STORAGE permission denied" on Android 13
```
Cause: READ_EXTERNAL_STORAGE is removed on API 33+; replaced by granular permissions
Fix: Use version-adaptive permission request:
```
```kotlin
val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
ActivityCompat.requestPermissions(activity, permissions, REQUEST_STORAGE)
```

---

## Lifecycle Crashes

### "LifecycleOwner is already in DESTROYED state"
```
Cause: Starting camera after activity/fragment is destroyed
Fix: Check lifecycle state before binding
```
```kotlin
if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
    bindCamera()
}
```

### "Cannot access database on the main thread"
```
Cause: Room database query on UI thread
Fix: Use coroutines with IO dispatcher
```
```kotlin
viewModelScope.launch(Dispatchers.IO) {
    val photos = photoDao.getAllPhotos()
    withContext(Dispatchers.Main) {
        _photosState.value = photos
    }
}
```

### "ViewModel not found" / Hilt ViewModel injection crash
```
Cause: Activity not annotated with @AndroidEntryPoint
Fix:
```
```kotlin
@AndroidEntryPoint  // ← REQUIRED for Hilt ViewModels
class MainActivity : AppCompatActivity() {
    private val viewModel: CameraViewModel by viewModels()
}
```

---

## ProGuard / R8 Crashes (Release Only)

### "ClassNotFoundException" in release build but not debug
```
Symptoms: App works in debug, crashes on launch in release
Cause: R8 stripped a class used via reflection
Fix: Add keep rule:
```
```proguard
# Find the class from the crash log:
# java.lang.ClassNotFoundException: com.example.SomeClass
-keep class com.example.SomeClass { *; }

# If it's a whole package:
-keep class com.example.camera.** { *; }
```

### "NoSuchMethodException" in release build
```
Cause: R8 renamed or removed a method accessed via reflection
Fix:
```
```proguard
-keepclassmembers class com.example.MyClass {
    public void theMethodName(***);
}
```

### "Room fails silently" in release
```
Cause: R8 strips Room entity classes or DAOs
Fix:
```
```proguard
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}
```

### Debugging R8 issues
```bash
# See what R8 is actually keeping/removing:
# Add to proguard-rules.pro:
-printconfiguration build/outputs/logs/merged-rules.txt
-printusage build/outputs/logs/removed-code.txt
-printmapping build/outputs/logs/mapping.txt

# Then build and inspect those files
./gradlew assembleRelease
cat build/outputs/logs/removed-code.txt | grep "camera"
```

---

## Orientation & UI Crashes

### Preview stretched on tablet / foldable
```kotlin
// Fix: Use COMPATIBLE mode for cross-device support
val previewView = PreviewView(context).apply {
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FILL_CENTER
}
```

### "Surface has been released" crash on fast rotation
```kotlin
// Fix: Use a single-thread executor for camera operations
private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

override fun onDestroy() {
    super.onDestroy()
    cameraExecutor.shutdown()
}
```
