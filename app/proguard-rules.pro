# ProGuard / R8 Rules for Floating Network Monitor
# Memastikan Service dan Coroutines berjalan optimal saat r8 code shrinking / minification diaktifkan

-keepclassmembers class * extends android.app.Service {
    public void onCreate();
    public int onStartCommand(android.content.Intent, int, int);
    public void onDestroy();
}
