# Минификация выключена (isMinifyEnabled = false);
# правила ниже защищают библиотеки, если минификация будет включена.

# ZXing core (генерация QR)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ML Kit — распознавание QR
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Сущность Room (генерированный код обращается к полям напрямую)
-keep class ru.pcinventory.app.data.Item { *; }
