# Strip any android.util.Log calls from release builds (the app does not log health data,
# and this guarantees no accidental logging survives into a release).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
