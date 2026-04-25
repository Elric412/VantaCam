# Camera Advanced Reference

## Camera2 — Full RAW Capture Pipeline

### RAW/DNG Capture (Maximum Image Quality)

```kotlin
class RawCaptureManager(
    private val context: Context,
    private val cameraId: String
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    
    // Check device RAW support
    fun supportsRAW(): Boolean {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        return caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
    }
    
    // Get maximum RAW resolution
    fun getMaxRawSize(): Size {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
        return rawSizes.maxByOrNull { it.width * it.height }
            ?: throw UnsupportedOperationException("No RAW sizes available")
    }
    
    // Setup RAW ImageReader
    fun createRawImageReader(size: Size): ImageReader {
        return ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.RAW_SENSOR,
            2 // max images in queue
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    saveRawImage(image)
                } finally {
                    image.close()
                }
            }, Handler(HandlerThread("RawSaver").also { it.start() }.looper))
        }
    }
    
    // Capture RAW with manual settings
    fun captureRaw(
        surface: Surface,
        iso: Int = 100,
        exposureNs: Long = 10_000_000L, // 1/100s
        awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    ) {
        val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.apply {
            addTarget(surface)
            
            // Manual sensor control
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            
            // White balance
            set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            
            // Noise reduction
            set(CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            
            // Edge enhancement
            set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            
            // Lens shading correction (required for DNG)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            
            // Hot pixel correction
            set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
        }
        
        captureSession.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // TotalCaptureResult contains all metadata needed for DNG
                saveDNG(capturedImage, result)
            }
            
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                Log.e(TAG, "RAW capture failed: reason=${failure.reason}")
            }
        }, Handler(Looper.getMainLooper()))
    }
    
    // Save as DNG using Android's DngCreator
    private fun saveDNG(image: Image, result: TotalCaptureResult) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val dngCreator = DngCreator(characteristics, result)
        
        // Optional: set orientation
        dngCreator.setOrientation(ExifInterface.ORIENTATION_NORMAL)
        
        // Save to MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "RAW_${System.currentTimeMillis()}.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/RAW")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)!!
        
        resolver.openOutputStream(uri)?.use { outputStream ->
            dngCreator.writeImage(outputStream, image)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        
        dngCreator.close()
    }
    
    companion object { private const val TAG = "RawCaptureManager" }
}
```

---

## Manual Controls — ISO, Shutter, White Balance

### Query Supported Ranges
```kotlin
fun queryCameraCapabilities(cameraId: String): CameraCapabilities {
    val chars = cameraManager.getCameraCharacteristics(cameraId)
    
    // ISO range
    val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    // Shutter speed range (nanoseconds)
    val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    // Aperture values
    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
    // Focus distance range
    val focusRange = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    // AF modes
    val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
    // AWB modes
    val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
    // AE modes (including flash)
    val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
    // Optical Stabilization
    val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
    // Hardware level
    val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    
    Log.d(TAG, """
        Camera $cameraId capabilities:
        - ISO range: $isoRange
        - Exposure range: $exposureRange ns
        - Apertures: ${apertures?.joinToString()}
        - Hardware level: ${when(hwLevel) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "UNKNOWN"
        }}
    """.trimIndent())
    
    return CameraCapabilities(
        minIso = isoRange?.lower ?: 0,
        maxIso = isoRange?.upper ?: 0,
        minExposureNs = exposureRange?.lower ?: 0L,
        maxExposureNs = exposureRange?.upper ?: 0L,
        supportsManual = hwLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    )
}

data class CameraCapabilities(
    val minIso: Int,
    val maxIso: Int,
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val supportsManual: Boolean
)
```

### CameraX + Camera2Interop (Manual Controls without full Camera2)
```kotlin
// Use Camera2Interop to inject manual settings into CameraX
fun buildManualImageCapture(iso: Int?, exposureNs: Long?): ImageCapture {
    val builder = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

    if (iso != null || exposureNs != null) {
        Camera2Interop.Extender(builder).apply {
            if (iso != null) {
                setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            }
            if (exposureNs != null) {
                setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            }
        }
    }
    
    return builder.build()
}
```

---

## HDR Capture — Software HDR

```kotlin
// Software HDR: Capture multiple exposures, merge
class SoftwareHDR(private val cameraDevice: CameraDevice) {
    
    private val exposureStops = listOf(-2.0, 0.0, +2.0) // EV stops
    
    fun captureHDRBracket(
        surface: Surface,
        baseExposureNs: Long,
        baseIso: Int,
        onComplete: (List<Image>) -> Unit
    ) {
        val capturedImages = mutableListOf<Image>()
        val totalCaptures = exposureStops.size
        var completedCaptures = 0
        
        exposureStops.forEach { evStop ->
            val adjustedExposure = (baseExposureNs * Math.pow(2.0, evStop)).toLong()
                .coerceIn(1_000_000L, 1_000_000_000L) // 1ms to 1s
            
            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, adjustedExposure)
                set(CaptureRequest.SENSOR_SENSITIVITY, baseIso)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            }
            
            captureSession.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession,
                    request: CaptureRequest, result: TotalCaptureResult) {
                    completedCaptures++
                    if (completedCaptures == totalCaptures) {
                        onComplete(capturedImages)
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }
    }
}
```

---

## Multi-Camera Support

```kotlin
// Discover all cameras and their types
fun discoverCameras(): List<CameraInfo> {
    return cameraManager.cameraIdList.map { id ->
        val chars = cameraManager.getCameraCharacteristics(id)
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.physicalCameraIds
        } else emptySet()
        
        CameraInfo(
            id = id,
            facing = when(facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            },
            focalLengths = focalLengths?.toList() ?: emptyList(),
            isLogical = physicalIds.isNotEmpty()
        )
    }
}

data class CameraInfo(
    val id: String,
    val facing: String,
    val focalLengths: List<Float>,
    val isLogical: Boolean // logical = multiple physical cameras
)
```

---

## CameraX Preview in Jetpack Compose

```kotlin
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onSurfaceProviderReady: (Preview.SurfaceProvider) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    
    // Provide the surface provider to the caller
    LaunchedEffect(previewView) {
        onSurfaceProviderReady(previewView.surfaceProvider)
    }
    
    AndroidView(
        factory = { previewView },
        modifier = modifier,
        update = { /* no-op, managed by CameraX lifecycle */ }
    )
}

// Usage:
@Composable
fun CameraScreen(viewModel: CameraViewModel = hiltViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onSurfaceProviderReady = { surfaceProvider ->
                viewModel.bindCamera(lifecycleOwner, surfaceProvider)
            }
        )
        
        // Controls overlay
        CameraControlsOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            onCapture = { viewModel.capturePhoto(/* executor */) { } },
            onFlipCamera = { viewModel.flipCamera() }
        )
    }
}
```

---

## EXIF Metadata Handling

```kotlin
// Read EXIF from saved image
fun readExif(uri: Uri, context: Context): Map<String, String> {
    val exif = context.contentResolver.openInputStream(uri)?.use { inputStream ->
        ExifInterface(inputStream)
    } ?: return emptyMap()
    
    return mapOf(
        "Make" to (exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Unknown"),
        "Model" to (exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Unknown"),
        "ISO" to (exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: "Unknown"),
        "Exposure" to (exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "Unknown"),
        "Aperture" to (exif.getAttribute(ExifInterface.TAG_APERTURE_VALUE) ?: "Unknown"),
        "FocalLength" to (exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH) ?: "Unknown"),
        "Latitude" to (exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) ?: "Unknown"),
        "Longitude" to (exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) ?: "Unknown"),
        "DateTime" to (exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Unknown"),
        "Width" to (exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: "Unknown"),
        "Height" to (exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: "Unknown"),
    )
}

// Write EXIF to a file
fun writeExif(file: File, iso: Int, exposureNs: Long) {
    val exif = ExifInterface(file.absolutePath)
    exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "${exposureNs.toDouble() / 1_000_000_000.0}")
    exif.saveAttributes()
}
```

---

## Flash Control

```kotlin
// CameraX flash modes
fun setFlashMode(imageCapture: ImageCapture, mode: String) {
    imageCapture.flashMode = when (mode) {
        "AUTO" -> ImageCapture.FLASH_MODE_AUTO
        "ON"   -> ImageCapture.FLASH_MODE_ON
        "OFF"  -> ImageCapture.FLASH_MODE_OFF
        else   -> ImageCapture.FLASH_MODE_OFF
    }
}

// Torch (continuous flashlight)
fun toggleTorch(camera: Camera, enable: Boolean) {
    camera.cameraControl.enableTorch(enable)
}

// Check flash availability
fun hasFlash(camera: Camera): Boolean {
    return camera.cameraInfo.hasFlashUnit()
}
```
