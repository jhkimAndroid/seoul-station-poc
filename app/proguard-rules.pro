# ──────────────────────────────────────────────
# 디버그 스택 트레이스 보존
# ──────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ──────────────────────────────────────────────
# Kotlin
# ──────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ──────────────────────────────────────────────
# OkHttp / Okio
# ──────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ──────────────────────────────────────────────
# Kakao Map SDK
# ──────────────────────────────────────────────
-keep class com.kakao.vectormap.** { *; }
-keep interface com.kakao.vectormap.** { *; }
-dontwarn com.kakao.vectormap.**

# ──────────────────────────────────────────────
# Google Play Services (Location)
# ──────────────────────────────────────────────
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ──────────────────────────────────────────────
# AndroidX / Jetpack Compose
# ──────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ──────────────────────────────────────────────
# 앱 내부 모델 (서버 JSON 파싱에 사용되는 클래스)
# ──────────────────────────────────────────────
-keep class com.hubilon.seoulstationpoc.data.** { *; }
-keepclassmembers class com.hubilon.seoulstationpoc.data.** {
    <fields>;
}
