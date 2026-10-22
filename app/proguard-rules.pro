# OSMDroid
-keep class org.osmdroid.** { *; }
-keep class org.osmdroid.tileprovider.** { *; }
-keep class org.osmdroid.views.** { *; }
-keep class org.osmdroid.util.** { *; }
-keep class org.osmdroid.config.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Сохранить имена полей для data class
-keepclassmembers class com.netanalyzer.app.MainActivity$CellTower {
    <fields>;
}
