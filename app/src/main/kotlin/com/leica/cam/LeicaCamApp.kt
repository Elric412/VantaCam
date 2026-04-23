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
import javax.inject.Inject
import javax.inject.Named

/**
 * Application entry point for LeicaCam.
 *
 * D1.9: At startup, warms up all on-device AI models in a background coroutine
 * to amortise delegate compilation and first-inference cost off the capture path.
 *
 * Warm-up is sequential and uses bounded synthetic buffers sized per role so the
 * app does not need model-specific special cases in Application startup.
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

        // D1.9: Warm up all on-device AI models in a background coroutine so
        // the first shutter press after app start doesn't pay the delegate-compile cost.
        // Models are warmed sequentially with a bounded warm-up buffer size to stay
        // under the low-memory ceiling on devices without large heaps.
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
    }

    companion object {
        private const val TAG = "LeicaCamApp"
    }
}
