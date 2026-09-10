# yt-dlp is driven through the bundled Python interpreter via JNI and reflection,
# so the bridge classes must survive any obfuscation.
-keep class com.yausername.** { *; }
-keep class com.junkfood.** { *; }
-dontwarn com.yausername.**
