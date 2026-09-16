# ProGuard / R8 Rules for Floating Network Monitor
# Memastikan Service dan Coroutines berjalan optimal saat r8 code shrinking / minification diaktifkan

-keepclassmembers class * extends android.app.Service {
    public void onCreate();
    public int onStartCommand(android.content.Intent, int, int);
    public void onDestroy();
}

# Shizuku API
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.Shizuku {
    private static java.lang.Process newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

