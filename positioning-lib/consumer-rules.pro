# positioning-lib consumer rules
# 이 파일은 앱 모듈이 positioning-lib를 dependency로 포함할 때 자동으로 적용됩니다.

# 라이브러리 공개 API 보존
-keep public class com.hubilon.positioning.** { public *; }
-keep public interface com.hubilon.positioning.** { *; }

# OkHttp (positioning-lib 내부 사용)
-dontwarn okhttp3.**
-dontwarn okio.**

# Google Play Services Location (positioning-lib 내부 사용)
-dontwarn com.google.android.gms.**
