# ===== ML Kit =====
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ===== Tesseract =====
-keep class com.googlecode.tesseract.android.** { *; }
-keep class cz.adaptech.tesseract4android.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# ===== Material & AndroidX =====
-keep class com.google.android.material.** { *; }
-keep class androidx.** { *; }
-dontwarn androidx.**

# ===== Umum =====
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
