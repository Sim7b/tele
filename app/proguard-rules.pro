# Règles Proguard pour Android TV et ExoPlayer Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coroutines & Coil
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
