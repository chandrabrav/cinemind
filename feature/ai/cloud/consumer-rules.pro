# Anthropic Java SDK: Jackson reflection over SDK models, Kotlin metadata.
-keep class com.anthropic.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-dontwarn com.anthropic.**
-dontwarn com.fasterxml.jackson.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn kotlin.reflect.jvm.internal.**
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
# The SDK's JSON-schema generator (only for class-based schemas; we pass ours by hand)
# references desktop reflection APIs that Android lacks.
-dontwarn com.github.victools.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedArrayType
-dontwarn java.lang.reflect.AnnotatedWildcardType
-dontwarn java.lang.reflect.AnnotatedTypeVariable
