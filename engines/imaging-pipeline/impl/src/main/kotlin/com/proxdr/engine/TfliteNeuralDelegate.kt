/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  android/TfliteNeuralDelegate.kt                          ║
 * ║  Reference TFLite implementation of the NeuralDelegate interface        ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  Loads the scene-segmentation and portrait-matting TFLite models with   ║
 * ║  NNAPI/GPU/XNNPack delegates (in that priority order). All inference    ║
 * ║  happens here on the Android side; results are returned to the C++      ║
 * ║  engine via the NeuralDelegate interface.                               ║
 * ║                                                                          ║
 * ║  Models (place under /assets/models/):                                   ║
 * ║   • scene_seg_mobilenetv3.tflite   (256×192 → 6 classes softmax)         ║
 * ║   • portrait_mat_mobilenet.tflite  (256×192 → alpha matte)               ║
 * ║                                                                          ║
 * ║  Both can be sourced from:                                              ║
 * ║   • MediaPipe Selfie Segmentation (portrait matting)                    ║
 * ║   • DeepLabV3 MobileNetV3 (scene segmentation)                          ║
 * ║   • Or train your own with ADE20K / Cityscapes                          ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

package com.proxdr.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteNeuralDelegate(
    private val ctx: Context,
    private val segModelPath: String = "models/scene_seg_mobilenetv3.tflite",
    private val matModelPath: String = "models/portrait_mat_mobilenet.tflite",
    private val segInputW: Int = 256,
    private val segInputH: Int = 192,
    private val numClasses: Int = 6,    // 0=bg, 1=sky, 2=face, 3=veg, 4=water, 5=building
) : NeuralDelegate {

    companion object { private const val TAG = "TfliteDelegate" }

    private var segInterp: Interpreter? = null
    private var matInterp: Interpreter? = null
    private var nnapiDelegate: NnApiDelegate? = null
    private var gpuDelegate:   GpuDelegate?   = null

    init { tryLoad() }

    private fun tryLoad() {
        val opts = Interpreter.Options().apply {
            // Try NPU first (lowest power), fall back to GPU, then CPU XNNPack
            try {
                val nn = NnApiDelegate()
                addDelegate(nn); nnapiDelegate = nn
                Log.i(TAG, "NNAPI delegate active")
            } catch (t: Throwable) {
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    val g = GpuDelegate(); addDelegate(g); gpuDelegate = g
                    Log.i(TAG, "GPU delegate active")
                } else {
                    setNumThreads(2); setUseXNNPACK(true)
                    Log.i(TAG, "CPU XNNPack delegate active")
                }
            }
        }

        runCatching {
            val mb = FileUtil.loadMappedFile(ctx, segModelPath)
            segInterp = Interpreter(mb, opts)
        }.onFailure { Log.w(TAG, "scene-seg model not loaded: ${it.message}") }

        runCatching {
            val mb = FileUtil.loadMappedFile(ctx, matModelPath)
            matInterp = Interpreter(mb, Interpreter.Options())
        }.onFailure { Log.w(TAG, "portrait-mat model not loaded: ${it.message}") }
    }

    fun close() {
        segInterp?.close(); segInterp = null
        matInterp?.close(); matInterp = null
        nnapiDelegate?.close()
        gpuDelegate?.close()
    }

    // ─── NeuralDelegate interface ──────────────────────────────────────────
    override fun runSegmentation(rgb: FloatArray, w: Int, h: Int): FloatArray? {
        val interp = segInterp ?: return null
        // ProXDR sends already-resized RGB at the model's native resolution.
        require(w == segInputW && h == segInputH) {
            "seg input size mismatch: expected $segInputW×$segInputH, got $w×$h"
        }
        val inBuf = ByteBuffer.allocateDirect(rgb.size * 4).order(ByteOrder.nativeOrder())
        for (v in rgb) inBuf.putFloat(v)
        inBuf.rewind()

        val outFloats = FloatArray(w * h * numClasses)
        val outBuf = ByteBuffer.allocateDirect(outFloats.size * 4).order(ByteOrder.nativeOrder())
        try {
            interp.run(inBuf, outBuf)
            outBuf.rewind()
            for (i in outFloats.indices) outFloats[i] = outBuf.float
            return outFloats
        } catch (t: Throwable) {
            Log.e(TAG, "scene-seg inference failed: ${t.message}")
            return null
        }
    }

    override fun runPortraitMat(rgb: FloatArray, w: Int, h: Int): FloatArray? {
        val interp = matInterp ?: return null
        val inBuf = ByteBuffer.allocateDirect(rgb.size * 4).order(ByteOrder.nativeOrder())
        for (v in rgb) inBuf.putFloat(v)
        inBuf.rewind()

        val outFloats = FloatArray(w * h)
        val outBuf = ByteBuffer.allocateDirect(outFloats.size * 4).order(ByteOrder.nativeOrder())
        try {
            interp.run(inBuf, outBuf)
            outBuf.rewind()
            for (i in outFloats.indices) outFloats[i] = outBuf.float
            return outFloats
        } catch (t: Throwable) {
            Log.e(TAG, "portrait-mat inference failed: ${t.message}")
            return null
        }
    }
}
