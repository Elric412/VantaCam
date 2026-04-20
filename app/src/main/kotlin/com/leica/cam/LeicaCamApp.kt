package com.leica.cam

import android.app.Application
import android.util.Log
import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Named

/**
 * Application entry point for LeicaCam.
 *
 * D1.9: At startup, warms up all on-device AI models in a background coroutine
 * to amortise JIT / delegate-compile cost off the capture path.
 *
 * Target: < 2s total warm-up on the UI splash (sequential with GC between each
 * model to stay under the 400 MB OOM threshold on low-end devices).
 */
@HiltAndroidApp
class LeicaCamApp : Application() {

    @Inject
    lateinit var modelRegistry: ModelRegistry

    @Inject
    @Named("assetBytes")
    lateinit var assetBytesLoader: @JvmSuppressWildcards Function1<String, ByteBuffer>

    /** Application-scoped coroutine scope (survives configuration changes). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // D1.9: Warm up on-device models in background.
        // Sequential warm-up with GC hints between each to prevent OOM.
        // Only AwbModelRunner and FaceLandmarkerRunner are warmed eagerly;
        // MicroIspRunner and SemanticSegmenterRunner are deferred until first shutter press.
        appScope.launch {
            try {
                val warmedCount = modelRegistry.warmUpAll(assetBytesLoader)
                Log.i(TAG, "Model warm-up complete: $warmedCount models ready")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Model warm-up failed (non-fatal): ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "LeicaCamApp"
    }
}
