# JNA and lazysodium call into native code by reflection; keep them intact.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-dontwarn org.slf4j.**

# zxcvbn loads its dictionaries from resources.
-keep class com.nulabinc.zxcvbn.** { *; }

# Pointer-based libsodium bindings (keys in guarded memory), registered with JNA by class.
-keep class app.lightneet.core.crypto.SodiumNative { *; }
