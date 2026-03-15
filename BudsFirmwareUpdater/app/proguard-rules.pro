# Keep firmware-related classes from obfuscation
-keep class com.galaxybuds.firmwareupdater.firmware.** { *; }
-keep class com.galaxybuds.firmwareupdater.protocol.** { *; }

# Keep Gson serialization models
-keep class com.galaxybuds.firmwareupdater.firmware.FirmwareRemoteBinary { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
