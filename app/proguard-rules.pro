# Keep TFLite model metadata.
-keep class org.tensorflow.lite.** { *; }

# Keep Hilt generated components.
-keep class dagger.hilt.** { *; }

# Keep LiteRT private fields used by current model output-layout validation.
-keepclassmembers class com.leica.cam.ai_engine.impl.runtime.LiteRtSession {
    private <fields>;
}
