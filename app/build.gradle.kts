plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vex.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vex.client"
        minSdk = 21
        targetSdk = 34
        versionCode = 34
        versionName = "1"
        // فقط گوشی‌های ARM — حذف کتابخانه‌های x86/x86_64 شبیه‌ساز → APK حدود ۱۶MB کوچک‌تر
        // (دانلود سبک‌تر = ریسک خرابی و خطای «بسته معتبر نیست» به‌مراتب کمتر)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("vex") {
            storeFile = file("vex.keystore")
            storePassword = "vexVex2024"
            keyAlias = "vex"
            keyPassword = "vexVex2024"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("vex") }
        release {
            // سخت‌سازی: کدها obfuscate و فشرده می‌شوند — دیکامپایل/خواندن منطق برنامه سخت می‌شود
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("vex")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // lint vital روی release نباید بیلد را بیندازد — امضای دیجیتال و ساختار APK جداگانه در CI چک می‌شود
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // libbox.aar (هسته‌ی sing-box) از app/libs — JAR کلاس‌های سایفون مستثنا می‌شود
    // (پایین با implementation صریح داده می‌شود)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"),
        "exclude" to listOf("psiphon-classes.jar"))))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    // ضربان پس‌زمینه‌ی اعلان‌های مدیر — حتی وقتی برنامه بسته است (سیستم هر ۱۵ دقیقه بیدار می‌کند)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // دوربین برای اسکنر QR
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // دیکودر QR به‌صورت آفلاین (مدل داخل APK؛ بدون نیاز به گوگل‌پلی)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // موتور سایفون (Psiphon tunnel core) — کلاس‌های استریپ‌شده (بدون go.* تکراری) به‌صورت
    // JAR ساده از CI + کتابخانه‌ی بومی libgojni.so مستقیماً در src/main/jniLibs؛
    // تونل در پروسه‌ی جدا «:psiphon» اجرا می‌شود (بدون هیچ ترنسفورم AAR)
    implementation(files("libs/psiphon-classes.jar"))
}
