# Keep TFLite model metadata.
-keep class org.tensorflow.lite.** { *; }

# Keep Hilt generated components.
-keep class dagger.hilt.** { *; }

# Keep LiteRT private fields used by current model output-layout validation.
-keepclassmembers class com.leica.cam.ai_engine.impl.runtime.LiteRtSession {
    private <fields>;
}

# Suppress warnings for annotation processor classes not present at runtime.
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedAnnotationTypes
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8

# Suppress warnings for TFLite GPU delegate classes that may be absent on some devices.
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
