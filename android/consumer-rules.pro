# youtube_muxer_2025 consumer ProGuard rules
# Applied automatically to any app that depends on this plugin

-keep class com.example.youtube_muxer_2025.** { *; }

-keep class org.schabi.newpipe.** { *; }
-keepclassmembers class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**

-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okio.** { *; }

-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontwarn javax.script.ScriptEngineFactory
-dontwarn org.mozilla.javascript.**
