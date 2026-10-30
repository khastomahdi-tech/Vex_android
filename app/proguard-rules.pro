# ===== VEX Client — قواعد R8 (سخت‌سازی نسخه‌ی release) =====

# --- هسته‌ی sing-box (libbox): JNI و کالبک‌ها — نباید هیچ‌چیزش حذف/تغییرنام شود ---
-keep class io.nekohasekai.** { *; }
-keep interface io.nekohasekai.** { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.**

# --- attributes لازم برای JNI و جنریک‌ها ---
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

# --- Parcelable / Serializable (مثل بقیه‌ی اپ‌های اندرویدی) ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# --- enum ها (reflection داخل هسته/کتابخانه‌ها) ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- کلاس‌های داده‌ی برنامه (جابه‌جا با JSON ساخته می‌شوند ولی فیلدها با نام صریح استفاده‌اند؛
#     با این حال اعضای عمومی نگه داشته می‌شوند تا هیچ ریسکی نباشد) ---
-keep class com.vex.client.Server { *; }
-keep class com.vex.client.VexSettings { *; }
-keep class com.vex.client.TelegramGate$* { *; }
-keep class com.vex.client.IntegrityGate { *; }
-keep class com.vex.client.Notifier { *; }

# --- ورودی‌های سیستم: Activity/Service و callback های اندروید ---
-keep public class com.vex.client.MainActivity { public *; }
-keep public class com.vex.client.QrScanActivity { public *; }
-keep public class com.vex.client.VexVpnService { public protected *; }

# --- گوگل (ML Kit / CameraX) قواعد خودشان را دارند؛ هشدارهای اضافه بی‌خطرند ---
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn androidx.camera.**
-dontwarn org.slf4j.**
-dontwarn java.lang.invoke.**

# حذف لاگ‌های خام در خروجی release (کوچک‌تر + اطلاعات کمتر برای تحلیل‌گر)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
