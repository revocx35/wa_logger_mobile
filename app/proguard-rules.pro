# kotlinx.serialization, Navigation, OkHttp, Media3, Coil, coroutines and Compose ship their own R8 rules.
# Models, settings and navigation routes are (de)serialized through generated serializers; keep them explicit.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Shrink and optimize, but don't rename: stack traces in the in-app crash reports stay readable without a
# mapping file.
-dontobfuscate

# OkHttp / Okio: optional platform integrations that are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
