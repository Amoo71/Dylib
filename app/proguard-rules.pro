# Reflection auf Android-Framework-Klassen (Bluetooth) braucht keine Keep-Regeln,
# HiddenApiBypass bringt eigene Consumer-Rules mit.
-dontwarn org.lsposed.hiddenapibypass.**

# AirPlay-Empfänger (jqssun/UxPlay): JNI ruft diese Klassen und Methoden über ihren Namen auf
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class * implements io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }
-keep class io.github.jqssun.airplay.discovery.** { *; }
