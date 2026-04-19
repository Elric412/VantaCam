# Android Camera2 Patterns — Deep Implementation Reference

> **Modules:** `:sensor-hal`, `:gpu-compute`, `:ai-engine`
> **Scope:** Camera2 API session lifecycle, RAW capture, Zero-Shutter-Lag (ZSL) with `TEMPLATE_ZERO_SHUTTER_LAG` + reprocessing, `ImageReader` configurations for RAW10/RAW12/RAW_SENSOR, `HardwareBuffer` zero-copy for GPU, TFLite delegate lifecycle (GPU / NNAPI / CPU fallback), and capability negotiation.
> **Design principle:** Camera2, not CameraX. The LUMO pipeline requires fine-grained per-frame control (manual exposure, raw burst timing, reprocessing sessions) that CameraX abstracts away.

---

## 0. Why Camera2 Over CameraX

CameraX is excellent for most apps. The LUMO pipeline specifically requires features CameraX does not expose:

| Feature | Camera2 | CameraX |
|---|---|---|
| `RAW_SENSOR` stream alongside `YUV` and `JPEG` | ✅ | ⚠️ Experimental / limited |
| Multi-camera (logical camera → physical sub-cameras) | ✅ | Partial |
| Reprocessing sessions (real ZSL, not best-effort) | ✅ | ❌ |
| Per-frame `SENSOR_EXPOSURE_TIME` / `SENSOR_SENSITIVITY` overrides | ✅ | ⚠️ via Camera2Interop |
| `SENSOR_NOISE_PROFILE`, `FORWARD_MATRIX1/2` metadata | ✅ | ❌ |
| HEIC + RAW + HDR10 metadata in one capture | ✅ | Limited |

Use CameraX for the app's simple "tap to take a selfie" preview if you want; use Camera2 for the LUMO production capture path.

---

## 1. Capability Negotiation

### 1.1 Required capabilities

Before declaring a device "LUMO-capable":

```kotlin
fun CameraCharacteristics.isLumoCapable(): Boolean {
    val capabilities = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
    val needed = intArrayOf(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS,
    )
    val set = capabilities.toSet()
    return needed.all { it in set }
}

fun CameraCharacteristics.supportsZsl(): Boolean {
    val caps = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
    return CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING in caps.toSet() ||
           CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING     in caps.toSet()
}

fun CameraCharacteristics.supports10Bit(): Boolean {
    val caps = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
    return CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT_OUTPUT in caps.toSet()
}
```

### 1.2 RAW format selection

```kotlin
fun CameraCharacteristics.bestRawFormat(): Int {
    val configMap = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    return when {
        ImageFormat.RAW_SENSOR in configMap.outputFormats -> ImageFormat.RAW_SENSOR  // 16-bit, preferred
        ImageFormat.RAW12      in configMap.outputFormats -> ImageFormat.RAW12
        ImageFormat.RAW10      in configMap.outputFormats -> ImageFormat.RAW10
        else -> throw UnsupportedOperationException("No RAW format supported")
    }
}
```

`RAW_SENSOR` is 16-bit linear RGGB (or equivalent CFA pattern) — the LUMO pipeline's preferred ingest format. It preserves full ADC precision; RAW10/RAW12 are packed formats that cost unpack CPU time.

### 1.3 Dump full metadata at first-run

Persist the following to `CameraCapabilityProfile`:

```kotlin
data class CameraCapabilityProfile(
    val cameraId: String,
    val pixelArraySize: Size,
    val activeArraySize: Rect,
    val preCorrectionActiveArraySize: Rect,
    val sensorOrientation: Int,
    val lensFocalLengths: FloatArray,
    val lensApertures: FloatArray,
    val hyperfocalDistance: Float,
    val minimumFocusDistance: Float,
    val blackLevelPattern: BlackLevelPattern,
    val whiteLevel: Int,
    val colorFilterArrangement: Int,
    val noiseProfile: DoubleArray,    // SENSOR_NOISE_PROFILE
    val forwardMatrix1: Rational,     // per DNG spec
    val forwardMatrix2: Rational,
    val colorMatrix1: Rational,
    val colorMatrix2: Rational,
    val cameraCalibration1: Rational,
    val cameraCalibration2: Rational,
    val calibrationIlluminant1: Int,
    val calibrationIlluminant2: Int,
    val availableIsoRange: Range<Int>,
    val availableExposureRange: Range<Long>,
    val maxDigitalZoom: Float,
    val supportsZsl: Boolean,
    val supports10Bit: Boolean,
)
```

This is constructed from `CameraCharacteristics` on first camera open and cached. The whole LUMO color science pipeline depends on the presence of `forwardMatrix1/2` and `noiseProfile`.

---

## 2. Zero-Shutter-Lag Implementation

### 2.1 Two ZSL strategies in Camera2

**Strategy A — `TEMPLATE_ZERO_SHUTTER_LAG` + `CONTROL_ENABLE_ZSL`:**

Simplest path. Request the ZSL template, set `ENABLE_ZSL = true`, issue capture requests. HAL may or may not honour it (device-dependent). On devices that honour it, the reported timestamp of the captured frame is "close to" the moment of capture, without re-running AE/AF convergence.

```kotlin
val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG)
builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
builder.addTarget(previewSurface)
builder.addTarget(rawImageReader.surface)
session.setRepeatingRequest(builder.build(), callback, handler)
```

**Strategy B — Reprocessing sessions (true ZSL, used by Google Camera, Samsung, OnePlus flagships):**

The only way to achieve sub-100ms shutter lag across all devices. Works like this:

1. Create a `ReprocessableCaptureSession` with an **input** configuration (e.g., `PRIVATE` or `YUV_420_888`) and multiple output configurations.
2. Continuously capture frames into a ring buffer backed by an `ImageWriter` feeding the session's input.
3. On shutter press, pick the best frame from the ring buffer by timestamp and submit a **reprocess capture request** that pipes that exact frame through the ISP again with high-quality settings (edge enhancement, noise reduction maxed).

Full code pattern:

```kotlin
class ZslReprocessSession(
    private val camera: CameraDevice,
    private val characteristics: CameraCharacteristics,
    private val previewSurface: Surface,
    private val handler: Handler,
) {
    private lateinit var inputImageReader:  ImageReader    // captures frames
    private lateinit var outputImageReader: ImageReader    // final JPEG/RAW
    private lateinit var session:           CameraCaptureSession
    private lateinit var imageWriter:       ImageWriter
    private val ring = ZeroShutterLagRingBuffer(depth = 20)

    fun open() {
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.PRIVATE).maxBy { it.width.toLong() * it.height }!!

        inputImageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.PRIVATE, /*maxImages=*/ 20
        )
        outputImageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.JPEG, /*maxImages=*/ 2
        )

        val inputCfg  = InputConfiguration(size.width, size.height, ImageFormat.PRIVATE)
        val outputCfgs = listOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(inputImageReader.surface),
            OutputConfiguration(outputImageReader.surface),
        )

        camera.createReprocessableCaptureSession(
            inputCfg, outputCfgs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    imageWriter = ImageWriter.newInstance(s.inputSurface!!, 20)
                    startContinuousCapture()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    throw RuntimeException("ZSL reprocess session failed")
                }
            },
            handler
        )
    }

    private fun startContinuousCapture() {
        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG).apply {
            addTarget(previewSurface)
            addTarget(inputImageReader.surface)
            set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
        }.build()

        session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                r: CaptureRequest,
                result: TotalCaptureResult
            ) {
                ring.enqueue(result.get(CaptureResult.SENSOR_TIMESTAMP)!!, result)
            }
        }, handler)

        inputImageReader.setOnImageAvailableListener({ reader ->
            reader.acquireNextImage()?.let { ring.attachImage(it) }
        }, handler)
    }

    /** User taps shutter. Pick the frame with the timestamp closest to press time. */
    fun captureAtShutterPress(shutterTimestamp: Long) {
        val bestEntry = ring.findClosest(shutterTimestamp) ?: return

        // Re-inject the image into the writer → triggers reprocess
        imageWriter.queueInputImage(bestEntry.image)

        // Reprocess request uses the previous TotalCaptureResult as its base
        val reprocess = camera.createReprocessCaptureRequest(bestEntry.result).apply {
            addTarget(outputImageReader.surface)
            set(CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.JPEG_QUALITY, 98.toByte())
        }.build()

        session.capture(reprocess, captureCallback, handler)
    }
}
```

Key points:
- `CONTROL_ENABLE_ZSL = true` on the **repeating** request (not just the reprocess).
- `inputImageReader` size should be the **full sensor output size**.
- `maxImages` on the input ImageReader must match the ring depth or larger — otherwise you'll drop frames.
- Match the `InputConfiguration` format with what `getInputFormats()` reports; some devices only support `YUV_420_888` reprocessing, not `PRIVATE`.

### 2.2 Burst capture for FusionLM

For a same-exposure burst (not ZSL reprocess), use `captureBurst`:

```kotlin
val reqs = (0 until frameCount).map {
    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
        addTarget(rawImageReader.surface)
        set(CaptureRequest.CONTROL_AE_LOCK, true)   // lock AE for the whole burst
        set(CaptureRequest.CONTROL_AWB_LOCK, true)
    }.build()
}
session.captureBurst(reqs, captureCallback, handler)
```

Lock AE and AWB before the burst starts — otherwise frame-to-frame exposure drift corrupts the FusionLM fusion.

### 2.3 EV-bracket capture for ProXDR

```kotlin
fun buildBracketRequests(
    base: CaptureRequest.Builder,
    evOffsets: List<Float>,
    baseExposureTime: Long,
    baseIso: Int,
): List<CaptureRequest> = evOffsets.map { ev ->
    base.apply {
        // EV = log2(exposureTime * iso / base)
        // Prefer shifting exposure time over ISO to preserve SNR
        val factor = 2.0.pow(ev.toDouble())
        val newExposure = (baseExposureTime * factor).toLong().coerceIn(
            MIN_EXPOSURE_NS, MAX_EXPOSURE_NS
        )
        val newIso = if (newExposure == (baseExposureTime * factor).toLong()) {
            baseIso
        } else {
            (baseIso * factor * baseExposureTime / newExposure).toInt()
        }
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, newExposure)
        set(CaptureRequest.SENSOR_SENSITIVITY, newIso)
    }.build()
}
```

---

## 3. ImageReader Configurations

### 3.1 RAW_SENSOR + preview + YUV analysis streams

```kotlin
val rawReader = ImageReader.newInstance(
    rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR,
    /*maxImages=*/ 12      // burst depth + headroom
)
val yuvReader = ImageReader.newInstance(
    analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888,
    /*maxImages=*/ 4
)
```

Stream combo legality is defined in the Camera2 compatibility matrix — check `MaximumSizeLevel` of the device:

| Hardware level | Max simultaneous streams | RAW + YUV + Preview? |
|---|---|---|
| LEGACY | 1 YUV | No |
| LIMITED | PRIV + YUV + JPEG | RAW not guaranteed |
| FULL | RAW + YUV + PRIV | ✅ |
| LEVEL_3 | RAW + YUV + PRIV + JPEG (reprocess) | ✅ |

Require `INFO_SUPPORTED_HARDWARE_LEVEL >= FULL` for LUMO default, `LEVEL_3` for full ZSL reprocess path.

### 3.2 Correct buffer release (this is where 80% of Camera2 bugs live)

```kotlin
val onImageAvailable = ImageReader.OnImageAvailableListener { reader ->
    // acquireNextImage can return null if you lag — that's fine
    val img: Image? = reader.acquireNextImage()
    if (img == null) { Log.w(TAG, "ZSL reader dropped frame"); return@OnImageAvailableListener }

    try {
        // Process or hand off to ring buffer
        ringBuffer.enqueue(img)
        // NB: img.close() called by ring buffer when evicted
    } catch (t: Throwable) {
        img.close()
        throw t
    }
}
```

Every `Image` **must** be closed. If you accumulate without closing, `ImageReader` silently stops producing after `maxImages`. Favour `use { }` patterns when not stashing in a ring buffer.

### 3.3 Unpacking RAW10 / RAW12

RAW10 packs 4 pixels into 5 bytes. RAW12 packs 2 pixels into 3 bytes. Unpack on the GPU to minimise CPU pressure:

```glsl
// Unpack RAW10 — 4 pixels per 5 bytes
// Bytes: [p0_hi8][p1_hi8][p2_hi8][p3_hi8][p0_lo2|p1_lo2|p2_lo2|p3_lo2]

int unpackRaw10(uint byteIndex, uint[] packedBytes) {
    int pixelInGroup = int(byteIndex % 4);
    int groupBase = int(byteIndex / 4) * 5;
    uint hi = packedBytes[groupBase + pixelInGroup];
    uint lo = packedBytes[groupBase + 4];
    uint loBits = (lo >> uint(pixelInGroup * 2)) & 3u;
    return int((hi << 2) | loBits);
}
```

`RAW_SENSOR` is unpacked 16-bit — no processing needed — so prefer it when available.

---

## 4. HardwareBuffer Zero-Copy to GPU

### 4.1 Why HardwareBuffer

`ImageReader` gives you `Image` objects backed by `ByteBuffer` in kernel memory. Copying that to a GPU texture for processing costs ~5 ms on mid-range devices (2 MPix × 2 bytes × 1.5 μs/MB). With `HardwareBuffer`, the kernel-allocated buffer can be **directly imported** into Vulkan / OpenGL as a texture — zero copy.

### 4.2 Creating a HardwareBuffer-backed ImageReader

```kotlin
val rawReader = ImageReader.newInstance(
    rawSize.width, rawSize.height,
    ImageFormat.RAW_SENSOR,
    /*maxImages=*/ 12,
    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN
)
```

Usage flags tell the HAL to allocate in memory region accessible to both GPU and CPU. For RAW_SENSOR processing, we typically use both — noise-model RMS is cheaper on CPU while Wiener merge is on GPU.

### 4.3 Importing HardwareBuffer into Vulkan

```kotlin
fun importHardwareBufferToVulkan(hb: HardwareBuffer): VulkanImage {
    val ahbDesc = VkAndroidHardwareBufferFormatPropertiesANDROID()
    val ahbProps = VkAndroidHardwareBufferPropertiesANDROID().apply {
        pNext = ahbDesc.pNext
    }
    vkGetAndroidHardwareBufferPropertiesANDROID(device, hb, ahbProps)

    val externalImageCi = VkExternalMemoryImageCreateInfo().apply {
        handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID
    }
    val imageCi = VkImageCreateInfo().apply {
        pNext = externalImageCi
        extent = VkExtent3D(hb.width, hb.height, 1)
        format = ahbDesc.format
        usage = VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_STORAGE_BIT
        // ...
    }
    val image = vkCreateImage(device, imageCi)

    val importCi = VkImportAndroidHardwareBufferInfoANDROID().apply {
        buffer = hb
    }
    val allocCi = VkMemoryAllocateInfo().apply {
        pNext = importCi
        allocationSize = ahbProps.allocationSize
        memoryTypeIndex = findMemoryTypeIndex(ahbProps.memoryTypeBits, 0)
    }
    val mem = vkAllocateMemory(device, allocCi)
    vkBindImageMemory(device, image, mem, 0)

    return VulkanImage(image, mem, hb.width, hb.height, ahbDesc.format)
}
```

Extensions required:
- `VK_KHR_external_memory`
- `VK_ANDROID_external_memory_android_hardware_buffer`

Available on all Android 8.0+ devices with Vulkan 1.1+ driver, which is 99%+ of LUMO target.

### 4.4 OpenGL ES fallback

```kotlin
// EGL side
val eglImage = EGL14.eglCreateImageKHR(
    eglDisplay, EGL14.EGL_NO_CONTEXT,
    EGL_NATIVE_BUFFER_ANDROID,
    hardwareBuffer.nativeHandle,
    intArrayOf(EGL14.EGL_NONE), 0
)
GLES30.glEGLImageTargetTexture2DOES(GLES30.GL_TEXTURE_EXTERNAL_OES, eglImage)
```

---

## 5. TFLite Delegate Lifecycle

### 5.1 Delegate priority

```kotlin
class TfliteModelManager(
    private val modelAsset: String,
    private val preferredBackend: Backend = Backend.GPU_THEN_NNAPI_THEN_CPU,
) {
    enum class Backend { GPU_ONLY, NNAPI_ONLY, CPU_ONLY, GPU_THEN_NNAPI_THEN_CPU }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null

    fun load(context: Context) {
        val model = FileUtil.loadMappedFile(context, modelAsset)
        val opts = Interpreter.Options().apply { setNumThreads(2) }

        val tried = mutableListOf<String>()

        when (preferredBackend) {
            Backend.GPU_ONLY, Backend.GPU_THEN_NNAPI_THEN_CPU -> {
                try {
                    val compat = CompatibilityList()
                    if (compat.isDelegateSupportedOnThisDevice) {
                        gpuDelegate = GpuDelegate(compat.bestOptionsForThisDevice)
                        opts.addDelegate(gpuDelegate)
                        interpreter = Interpreter(model, opts)
                        Log.i(TAG, "TFLite loaded on GPU delegate")
                        return
                    }
                } catch (t: Throwable) { tried += "gpu: ${t.message}" }
            }
            else -> {}
        }

        when (preferredBackend) {
            Backend.NNAPI_ONLY, Backend.GPU_THEN_NNAPI_THEN_CPU -> {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        nnapiDelegate = NnApiDelegate(
                            NnApiDelegate.Options().apply {
                                executionPreference = NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
                            }
                        )
                        opts.addDelegate(nnapiDelegate)
                        interpreter = Interpreter(model, opts)
                        Log.i(TAG, "TFLite loaded on NNAPI delegate")
                        return
                    }
                } catch (t: Throwable) { tried += "nnapi: ${t.message}" }
            }
            else -> {}
        }

        // CPU fallback
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        Log.w(TAG, "TFLite falling back to CPU. Tried: $tried")
    }

    fun run(input: Array<Any>, output: MutableMap<Int, Any>) {
        interpreter!!.runForMultipleInputsOutputs(input, output)
    }

    fun close() {
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
        nnapiDelegate?.close(); nnapiDelegate = null
    }
}
```

### 5.2 NNAPI caveats (2025 and forward)

Google has announced NNAPI deprecation in favour of **LiteRT (TFLite) in Google Play Services** with hardware accelerator delegates. For **new LUMO development targeting API 31+**, prefer:

1. **GPU delegate** (TFLite built-in, most reliable).
2. **Play Services TFLite runtime** which auto-routes to vendor accelerators.
3. Vendor-specific delegates only when measured to be 2× faster than GPU (Hexagon, ML-Accel).

NNAPI remains a compatibility fallback for API 27–30 only.

### 5.3 Quantisation

All LUMO on-device models are **INT8 quantised** with float32 input/output (i.e., "integer-only" not "dynamic range"). The GPU delegate requires float weights; it will internally dequant at runtime. For NNAPI / vendor delegates, keep the model fully INT8 for best throughput.

Calibration dataset: 500 representative images from the LUMO dev corpus. Per-tensor symmetric quantisation. Expect < 0.5% accuracy loss vs fp32 on MobileNetV3-class backbones.

### 5.4 Model distribution

- **In APK (< 3 MB total):** the three smallest models — scene classifier, WB CNN, light AF predictor.
- **Downloaded via WorkManager on WiFi + charging:**
  - Spectral reconstruction MLP (~800 KB)
  - MiDaS-lite depth (~6 MB)
  - Real-ESRGAN super-resolution (~50 MB, x4 variant)
  - DeepLabV3+ MobileNet (~4 MB)
  - Neural ISP denoising U-Net (~8 MB)

Signed manifest `models/manifest.json` with per-model SHA-256 and min app version. Fetch from a CDN behind a CloudFront (or Cloudflare) distribution.

### 5.5 Memory pressure management

```kotlin
class AiModelManager(private val budgetMb: Int = 256) {
    private val loaded = mutableMapOf<String, TfliteModelManager>()
    private val usageScore = mutableMapOf<String, Float>()

    fun ensureLoaded(name: String): TfliteModelManager {
        if (loaded.containsKey(name)) {
            usageScore[name] = (usageScore[name] ?: 0f) + 1f
            return loaded[name]!!
        }

        val available = ActivityManager.MemoryInfo().let {
            (activityManager.getMemoryInfo(it); it).availMem / 1024 / 1024
        }

        while (available < budgetMb && loaded.isNotEmpty()) {
            val victim = usageScore.minBy { it.value }.key
            loaded[victim]?.close()
            loaded.remove(victim); usageScore.remove(victim)
            Log.i(TAG, "Unloaded $victim due to memory pressure")
        }

        val mgr = TfliteModelManager(name)
        mgr.load(context)
        loaded[name] = mgr
        usageScore[name] = 1f
        return mgr
    }
}
```

Score by `in_use * recency * (1/size)` for a more sophisticated heuristic. Call `ensureLoaded` lazily — don't preload everything at startup.

---

## 6. CameraSessionManager — Full Lifecycle FSM

States:

```
CLOSED → OPENING → OPENED → CONFIGURING → CONFIGURED → PREVIEWING
                                                          ↓
                                                      CAPTURING
                                                          ↓
                                                      PREVIEWING (back)
                                                          ↓
                                                      CLOSING → CLOSED
```

Guard transitions with an explicit state machine. Do not leak state changes into UI code — surface only high-level `CameraState` (Ready / Capturing / Error) via a `StateFlow`.

```kotlin
class CameraSessionStateMachine {
    private val _state = MutableStateFlow<State>(State.Closed)
    val state: StateFlow<State> = _state

    sealed class State {
        object Closed : State()
        object Opening : State()
        data class Opened(val device: CameraDevice) : State()
        data class Configuring(val device: CameraDevice) : State()
        data class Previewing(val device: CameraDevice, val session: CameraCaptureSession) : State()
        data class Capturing(val device: CameraDevice, val session: CameraCaptureSession) : State()
        data class Error(val cause: Throwable) : State()
    }

    fun transition(next: State) {
        val current = _state.value
        require(isLegal(current, next)) { "Illegal transition: $current → $next" }
        _state.value = next
    }

    private fun isLegal(from: State, to: State): Boolean = when (from) {
        State.Closed -> to is State.Opening || to is State.Error
        State.Opening -> to is State.Opened || to is State.Error
        is State.Opened -> to is State.Configuring || to is State.Error
        is State.Configuring -> to is State.Previewing || to is State.Error
        is State.Previewing -> to is State.Capturing || to is State.Closed || to is State.Error
        is State.Capturing -> to is State.Previewing || to is State.Closed || to is State.Error
        is State.Error -> to is State.Closed
    }
}
```

### 6.1 AE/AF precapture sequence (shutter press)

```kotlin
suspend fun lockAndCapture(session: CameraCaptureSession) {
    // 1. Trigger AF convergence
    val afTrigger = previewBuilder.apply {
        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
    }.build()
    session.capture(afTrigger, null, handler)

    // 2. Wait for AF_STATE_FOCUSED_LOCKED or AF_STATE_NOT_FOCUSED_LOCKED (100–500 ms typical)
    awaitAfConvergence(timeoutMs = 500)

    // 3. Trigger AE precapture
    val aeTrigger = previewBuilder.apply {
        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
    }.build()
    session.capture(aeTrigger, null, handler)

    // 4. Wait for AE_STATE_CONVERGED (50–250 ms typical)
    awaitAeConvergence(timeoutMs = 300)

    // 5. Now capture (or trigger ZSL reprocess)
    performCapture(session)

    // 6. Clear triggers
    previewBuilder.apply {
        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
    }
    session.setRepeatingRequest(previewBuilder.build(), null, handler)
}
```

---

## 7. Real-Time Preview Metadata Streaming

For the viewfinder's histogram, face boxes, waveform, etc., run a YUV_420_888 analysis stream at 30 fps and compute metadata on the GPU (via RenderScript or custom OpenGL compute).

```kotlin
class YuvAnalysisStream(
    private val imageReader: ImageReader,
    private val gpu: GpuBackend,
    private val scope: CoroutineScope,
) {
    fun start() {
        imageReader.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            scope.launch {
                try {
                    val hb = img.hardwareBuffer ?: return@launch
                    val tex = gpu.importHardwareBuffer(hb)
                    val metadata = gpu.dispatch(
                        GpuKernel.PREVIEW_METADATA,
                        inputs = mapOf("yuv" to tex),
                    )
                    _metadataFlow.emit(metadata)
                } finally {
                    img.close()
                }
            }
        }, analysisHandler)
    }
}
```

Target budget: 8 ms per frame of preview metadata computation (leaves 24 ms for display compositing at 30 fps).

---

## 8. HDR10 + 10-Bit Output

For Android 13+ with `DYNAMIC_RANGE_TEN_BIT_OUTPUT`:

```kotlin
val hdr10Config = OutputConfiguration(hdrSurface).apply {
    dynamicRangeProfile = DynamicRangeProfiles.HDR10
}
val sessionConfig = SessionConfiguration(
    SessionConfiguration.SESSION_REGULAR,
    listOf(OutputConfiguration(previewSurface), hdr10Config),
    mainExecutor, sessionStateCallback
)
camera.createCaptureSession(sessionConfig)
```

Encode output HEIC with ISO/IEC 14496-12 Box for HDR10 static metadata:

```
<mastering-display-colour-volume>
  primaries: BT.2020
  white point: D65
  max luminance: 1000 nits
  min luminance: 0.005 nits
</mastering-display-colour-volume>
<content-light-level>
  max CLL: (measured from output)
  max FALL: (measured from output)
</content-light-level>
```

---

## 9. Thermal Governor Integration

```kotlin
class RuntimeGovernor(
    private val powerManager: PowerManager,
) {
    enum class ThermalState { NORMAL, MODERATE, SEVERE, CRITICAL }

    fun thermalState(): ThermalState {
        val status = if (Build.VERSION.SDK_INT >= 29) {
            powerManager.currentThermalStatus
        } else return ThermalState.NORMAL

        return when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT         -> ThermalState.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE      -> ThermalState.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE        -> ThermalState.SEVERE
            else                                      -> ThermalState.CRITICAL
        }
    }

    fun downgradePipeline(): PipelineDowngrade = when (thermalState()) {
        ThermalState.NORMAL    -> PipelineDowngrade.NONE
        ThermalState.MODERATE  -> PipelineDowngrade.DISABLE_NEURAL_ISP
        ThermalState.SEVERE    -> PipelineDowngrade.REDUCE_BURST_TO_3
        ThermalState.CRITICAL  -> PipelineDowngrade.SINGLE_FRAME_FALLBACK
    }
}
```

Register a `PowerManager.OnThermalStatusChangedListener` to react instantly rather than polling.

---

## 10. Debug & Observability

### 10.1 Per-frame capture trace

Attach an XMP `pc:ProcessingTrace` tag to every saved file:

```
pc:ProcessingTrace = [
  { "stage": "raw_ingest",        "t_ms":  2.1 },
  { "stage": "align",             "t_ms": 34.7 },
  { "stage": "fusion_lm_wiener",  "t_ms": 58.3 },
  { "stage": "color_lm",          "t_ms": 22.1 },
  { "stage": "depth_engine",      "t_ms": 41.0 },
  { "stage": "face_engine",       "t_ms": 28.5 },
  { "stage": "hypertone_wb",      "t_ms": 18.2 },
  { "stage": "bokeh",             "t_ms": 96.4 },
  { "stage": "tone_lm",           "t_ms": 24.8 },
  { "stage": "neural_isp",        "t_ms": 71.3 },
  { "stage": "encode_heic",       "t_ms": 19.6 }
]
```

### 10.2 Capture-replay mode

Save the RAW burst + all `TotalCaptureResult` metadata to disk in a `.lumo-capture` directory. Allow re-running the full pipeline on a desktop build of the native compute layer for debugging without re-capturing.

---

## 11. References

1. **Google.** *Android Camera2 API Reference.* [developer.android.com/reference/android/hardware/camera2](https://developer.android.com/reference/android/hardware/camera2). **The canonical API docs.**
2. **Google.** *Reduce latency with Zero-Shutter Lag.* [developer.android.com/media/camera/camerax/take-photo/zsl](https://developer.android.com/media/camera/camerax/take-photo/zsl). **CameraX ZSL; Camera2 has more control.**
3. **Google.** *Camera capture sessions and requests.* [developer.android.com/media/camera/camera2/capture-sessions-requests](https://developer.android.com/media/camera/camera2/capture-sessions-requests).
4. **Google.** *HardwareBuffer, VK_ANDROID_external_memory_android_hardware_buffer.* [Android NDK AHB guide](https://developer.android.com/ndk/reference/group/a-hardware-buffer).
5. **Google AOSP.** *Camera2AS (open-source reference).* [github.com/amirzaidi/Camera2AS](https://github.com/amirzaidi/Camera2AS). **Google Camera's ZSL reprocess implementation, read for patterns.**
6. **Google.** *LiteRT (TensorFlow Lite) Documentation: GPU delegate.* [tensorflow.org/lite/performance/gpu](https://www.tensorflow.org/lite/performance/gpu).
7. **Google.** *NNAPI migration guide.* [developer.android.com/ndk/guides/neuralnetworks/migration-guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide).
8. **Khronos.** *VK_ANDROID_external_memory_android_hardware_buffer extension.* [khronos.org](https://registry.khronos.org/vulkan/specs/1.3-extensions/html/vkspec.html#memory-external-android-hardware-buffer).
9. **Adobe.** *DNG Specification 1.7.* For understanding the metadata fields returned by Camera2 (`FORWARD_MATRIX1/2`, `COLOR_MATRIX1/2`, `CALIBRATION_ILLUMINANT1/2`).
10. **Android CTS Tests.** [android.googlesource.com platform/cts/tests/camera](https://android.googlesource.com/platform/cts/+/master/tests/camera/). **The definitive source for what really works on real devices.**
