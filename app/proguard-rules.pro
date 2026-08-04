# ONNX Runtime loads native code and uses reflection internally
-keep class ai.onnxruntime.** { *; }

# Anthropic SDK (Jackson-based serialization)
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
