# sherpa-onnx Kotlin API: native methods must keep their exact names and
# signatures so the JNI layer (System.loadLibrary + RegisterNatives / name
# resolution) keeps working after R8 shrinking.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Kotlin coroutines machinery used across the app.
-dontwarn kotlinx.coroutines.**

# Apache Commons Compress: only direct constructors are used (no
# CompressorStreamFactory reflection), but keep the stream classes intact to
# avoid surprises when upgrading the library.
-keep class org.apache.commons.compress.compressors.bzip2.** { *; }
-keep class org.apache.commons.compress.archivers.tar.** { *; }
