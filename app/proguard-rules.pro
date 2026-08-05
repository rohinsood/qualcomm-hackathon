# Native code looks classes and methods up by name (JNI RegisterNatives /
# GetMethodID) — R8 must not rename or strip any of these.
-keep class com.geniex.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mlkit.** { *; }

-dontwarn org.apache.commons.compress.**
-dontwarn com.geniex.**
