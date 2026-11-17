# A JVM-only enum used solely in Error Prone annotation metadata is absent on Android.
-dontwarn javax.lang.model.element.Modifier
# Only this Java/platform driver is a release instrumentation root. It avoids
# shared AndroidX/JUnit/Kotlin classes which target R8 may legitimately remove.
-keep class com.unarchive.android.editor.performance.NoteReleasePerformanceInstrumentation { *; }
-keep class com.unarchive.android.editor.performance.NoteReleasePerformanceInstrumentation$* { *; }
