import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "wzmwayne.shield"
    // 本机为 aarch64：只能用系统原生 aapt2(2.19)，它无法解析 android-35/36 的资源表，
    // 因此编译与目标版本定为 34；设计方案要求的运行下限 Android 9 (API 28) 不受影响。
    compileSdk = 34

    defaultConfig {
        applicationId = "wzmwayne.shield"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 0.1.0 先沿用 debug 签名发布（debuddbable=false），后续可换独立 release keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // 关键：让 jniLibs（含伪装成 .so 的激活脚本）在安装时被系统释放到 nativeLibraryDir，
        // 这样 adb 可以直接 sh 执行它，无需运行时写文件。
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // 仅在激活阶段使用 Shizuku；运行时不再调用任何 Shizuku API。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
