# Proguard rules for Android TV and Media3 ExoPlayer
# Media3 ships its own consumer rules (reflective extension loading): no blanket keep, so that R8 can
# shrink the unused players, extractors and decoders
-dontwarn androidx.media3.**

# Coroutines & Coil
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
