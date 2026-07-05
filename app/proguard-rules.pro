-keep class com.trinityvirtual.** { *; }
-keep class com.trinityvirtual.engine.** { *; }
-keep class com.trinityvirtual.spoof.** { *; }
-keep class com.trinityvirtual.module.** { *; }
-keepclassmembers class * {
    native <methods>;
}
