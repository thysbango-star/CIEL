# Vosk / JNA use reflection and native bindings.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
# Room entities and DAOs are generated; keep model classes used via reflection by Room.
-keep class dev.ciel.app.data.** { *; }
# JDK HTTP server is only used by tests.
-dontwarn com.sun.net.httpserver.**
