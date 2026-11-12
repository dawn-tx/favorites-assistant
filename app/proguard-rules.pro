# kotlinx.serialization：保留生成序列化器，否则 release 包同步请求体序列化会失败
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.favkit.tablet.**$$serializer { *; }
-keepclassmembers class com.favkit.tablet.** { *** Companion; }
-keepclasseswithmembers class com.favkit.tablet.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Media3 通过反射加载 extractor
-keep class androidx.media3.extractor.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
