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
 * D1.9: At startup, warms up ALL on-device AI models in a background coroutine
 * to amortise delegate compilation and first-inference cost off the capture path.
 *
 * All roles registered in [ModelRegistry.catalogue] are warmed sequentially.
 * Each warm-up run uses a role-specific synthetic buffer sized to match the
 * model's actual input/output tensor shapes — defined in
 * [ModelRegistry.warmUpInputSize] and [ModelRegistry.warmUpOutputSize].
 *
 * Roles without LiteRT tensor buffers (e.g. FACE_LANDMARKER, which is a
 * MediaPipe task) are skipped automatically inside [ModelRegistry.warmUpAll].
 *
 * The 250 ms initial delay gives the first preview frame time to reach the
 * GPU compositor before warm-up contends for memory.
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
        // All catalogue roles are warmed; roles without LiteRT buffers are skipped
        // automatically inside ModelRegistry.warmUpAll().
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
