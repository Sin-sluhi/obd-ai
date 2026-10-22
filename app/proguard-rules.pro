# OBD AI — правила R8 для релизной сборки (клиенты и Google Play).
# Рефлексии и библиотек сериализации в приложении нет: JSON разбирается вручную через org.json (он в Android),
# Compose и AndroidX несут свои правила сами. Поэтому почти ничего не держим — код запутывается целиком.

# Номера строк в стеке падения (Crash.kt шлёт его на сервер); имена классов и методов при этом запутаны,
# расшифровка — по mapping.txt из артефакта сборки (зашифрован APK_PASS).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Логи Log.d/Log.v в релизе не нужны
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
