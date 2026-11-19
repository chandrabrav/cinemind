# ProGuard rules for CineMind AI

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class com.cinemind.app.di.** { *; }

# Keep Room entities and DAOs
-keep class com.cinemind.core.domain.model.** { *; }
-keep class com.cinemind.core.data.local.dao.** { *; }
-keep class com.cinemind.core.data.local.database.** { *; }

# Keep Media3 classes
-keep class androidx.media3.** { *; }

# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }

# Keep ONNX Runtime classes
-keep class com.microsoft.onnxruntime.** { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep Kotlinx serialization
-keep class kotlinx.serialization.** { *; }

# Keep Ktor
-keep class io.ktor.** { *; }

# Keep Coil
-keep class io.coil.** { *; }

# Keep Timber
-keep class com.jakewharton.timber.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep Jetpack Compose
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

# Keep application classes
-keep class com.cinemind.app.** { *; }
-keep class com.cinemind.feature.** { *; }
-keep class com.cinemind.core.** { *; }
-keep class com.cinemind.media3.** { *; }
-keep class com.cinemind.benchmark.** { *; }

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI classes for llama.cpp
-keep class com.cinemind.feature.ai.llm.** { *; }

# Don't obfuscate Resource IDs
-keep class **.R
-keep class **.R$* {
    <fields>;
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep ViewModel
-keep class androidx.lifecycle.ViewModel { *; }
-keep class com.cinemind.** extends androidx.lifecycle.ViewModel { *; }

# Keep Navigation
-keep class androidx.navigation.** { *; }

# Keep Hilt ViewModel
-keep class dagger.hilt.android.lifecycle.HiltViewModelFactory { *; }

# Suppress warnings for libraries we don't control
-dontwarn dagger.hilt.**
-dontwarn androidx.media3.**
-dontwarn com.google.mediapipe.**
-dontwarn com.microsoft.onnxruntime.**
-dontwarn io.ktor.**
-dontwarn io.coil.**
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
-dontwarn okhttp3.**
-dontwarn okio.**
# Google LiteRT-LM (on-device Gemma / Spark): its native code looks up Java classes, fields and
# methods by name (JNI). Without this, R8 renamed what thinking models (Spark) use when a
# conversation starts → "JNI DETECTED ERROR: mid == null" in nativeCreateConversation (beta #198).
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keep enum com.google.ai.edge.litertlm.** { *; }

# Google LiteRT / TensorFlow Lite (Whisper speech model): JNI looks classes and methods up by name.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
