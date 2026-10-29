# Boards and backups are stored as JSON via kotlinx.serialization. The library
# ships its own rules; these keep the stored classes' names stable as well.
-keep,includedescriptorclasses class com.refboard.app.data.**$$serializer { *; }
-keepclassmembers class com.refboard.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.refboard.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.refboard.app.** { *; }

# WorkManager creates the worker by class name.
-keep class com.refboard.app.data.BoardDownloadWorker { <init>(...); }

# OkHttp optional platform classes
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
