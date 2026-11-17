# Only the explicit local performance build includes this platform-only facade.
-keep class com.unarchive.android.editor.performance.NoteReleasePerformanceHarness {
    public static void mount(android.app.Activity, android.os.Bundle, android.os.ResultReceiver);
    public static void command(android.app.Activity, android.os.Bundle);
}
