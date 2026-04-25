# Android Color Science Implementation

## Camera2 Metadata — What to Read on Every Device

```kotlin
// Read once at initialization
val chars = cameraManager.getCameraCharacteristics(cameraId)

// Sensor calibration (per-device, critical — never hardcode)
val blackLevels = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)   // Rational[4]
val whiteLevel  = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)       // Int

// Color matrices (D65 and StdA illuminants, per-sensor calibrated)
val ccm_D65  = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)         // Rational[9]
val ccm_StdA = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)         // Rational[9]
val fwd_D65  = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)          // Rational[9]
val fwd_StdA = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)          // Rational[9]

// Read on each CaptureResult (per-frame)
val wbGains   = result.get(CaptureResult.COLOR_CORRECTION_GAINS)                // RggbChannelVector
val awbMode   = result.get(CaptureResult.CONTROL_AWB_MODE)
val cctGuess  = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)            // Rational[3]
val noiseModel= result.get(CaptureResult.NOISE_REDUCTION_MODE)
```

## Two Processing Paths — One Pipeline

### Preview Path (30–60fps, GPU-accelerated)

```
Camera2 → YUV_420_888 → ImageReader → GPU (OpenGL ES 3.1)
   shader: WB + CCM + OKLAB + filmic curve + gamma → SurfaceView
```

### Capture Path (full quality, CPU/NDK)

```
Camera2 → RAW16 → ImageReader → NDK/C++
   linearize → LSC → NR → AHD demosaic → WB → CCM → OKLAB →
   hue-selective sat → filmic curve → LTM → gamma encode → JPEG/HEIC
```

**Critical rule:** Both paths use the same WB gains, same CCM matrix,
same curve parameters. The only difference is quality (AHD vs. bilinear
demosaic, full NR vs. simplified NR).

## GPU Shader Pipeline (OpenGL ES 3.1)

Core fragment shader structure:
```glsl
#version 310 es
precision highp float;

uniform sampler2D u_yuv;
uniform mat3      u_ccm;        // Camera RGB → linear sRGB (CCT-interpolated)
uniform vec3      u_wbGains;    // R, G, B white balance gains
uniform float     u_skinGuard;  // 0.0–1.0 skin protection strength
uniform float     u_filmicMid;  // midtone pivot (tune per aesthetic)

// OKLAB helpers, filmic curve, skin mask: see pipeline-math.md

void main() {
    vec3 yuv = texture(u_yuv, v_uv).rgb;
    vec3 rgb = yuvToLinearRgb(yuv);          // BT.601 full range
    rgb *= u_wbGains;                        // White balance
    rgb = u_ccm * rgb;                       // CCM: camera native → linear sRGB
    rgb = clamp(rgb, 0.0, 16.0);            // Headroom, NOT final clip

    vec3 lab = linearSrgbToOklab(rgb);       // → perceptual space
    lab = selectiveSaturation(lab, u_skinGuard); // Hue-selective, skin-protected
    lab = applyFilmicToL(lab);               // Filmic curve on luminance only
    rgb = oklabToLinearSrgb(lab);
    rgb = applySrgbGamma(rgb);               // Final encoding

    fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
```

## NDK / C++ Integration

```cmake
# CMakeLists.txt
cmake_minimum_required(VERSION 3.22)
project(color_science)
set(CMAKE_CXX_STANDARD 17)

add_library(color_science SHARED
    color_pipeline.cpp
    demosaic/ahd.cpp
    noise/bilateral.cpp
    color/oklab.cpp
    color/ccm.cpp
    color/filmic.cpp
)

target_link_libraries(color_science android log)
```

```kotlin
// Kotlin JNI bridge
class NativeColorPipeline {
    external fun processRaw(
        rawBytes: ByteArray,
        width: Int, height: Int,
        blackLevels: FloatArray,   // [R, Gr, Gb, B]
        whiteLevel: Float,
        wbGains: FloatArray,       // [R, G, B]
        ccm: FloatArray,           // 9 elements, row-major
        filmicParams: FloatArray,  // [A, B, C, D, E, F]
        skinProtection: Float
    ): IntArray  // output ARGB_8888 pixels

    companion object {
        init { System.loadLibrary("color_science") }
    }
}
```

## Threading Model

```
Main Thread
  └── CameraSession (Camera2 callbacks)
       ├── PreviewImageReader.OnImageAvailableListener
       │    └── GPU Thread (HandlerThread "GpuColorScience")
       │         └── OpenGL ES render → SurfaceView
       └── CaptureImageReader.OnImageAvailableListener
            └── Dispatchers.Default (Kotlin coroutine)
                 └── NativeColorPipeline.processRaw() [C++ NDK]
                      └── File write (JPEG/HEIC)
```

**Never run NDK color processing on the main thread.**
**Never block the Camera2 callback thread.**

## Color Space for Bitmaps (Android API)

```kotlin
// For output bitmap with Display-P3 support
val p3ColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
val config = Bitmap.Config.ARGB_8888

val bitmap = Bitmap.createBitmap(width, height, config, true, p3ColorSpace)
// Note: hasAlpha=true required for P3 bitmaps on API 26+

// For maximum compatibility, use sRGB:
val srgbBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
// Default ColorSpace is sRGB
```

## Capture Request Setup

```kotlin
captureRequestBuilder.apply {
    // Always capture RAW16 for full-quality processing
    addTarget(rawImageReader.surface)
    addTarget(previewSurface)

    // Let Camera2 do AWB estimate, but we read and override
    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

    // Disable in-camera NR and sharpening — we do our own
    set(CaptureRequest.NOISE_REDUCTION_MODE,
        CameraMetadata.NOISE_REDUCTION_MODE_OFF)
    set(CaptureRequest.EDGE_MODE,
        CameraMetadata.EDGE_MODE_OFF)

    // Disable tone mapping — we apply our own filmic curve
    set(CaptureRequest.TONEMAP_MODE,
        CameraMetadata.TONEMAP_MODE_FAST)

    // Use manual color correction with our CCM
    set(CaptureRequest.COLOR_CORRECTION_MODE,
        CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
    set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, rationalCCM)
    set(CaptureRequest.COLOR_CORRECTION_GAINS, wbGains)
}
```

## Handling Different RAW Formats

```kotlin
when (rawImage.format) {
    ImageFormat.RAW_SENSOR -> {
        // RAW16: 16-bit per pixel, Bayer RGGB (most common)
        val buffer: ByteBuffer = rawImage.planes[0].buffer
        val rowStride = rawImage.planes[0].rowStride
        // Row stride may be larger than width*2 — account for padding!
    }
    ImageFormat.RAW10 -> {
        // 10-bit packed — unpack before processing
        // 4 pixels in 5 bytes
    }
    ImageFormat.RAW12 -> {
        // 12-bit packed — 2 pixels in 3 bytes
    }
}
```
