plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tealtranquility.mikutan"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tealtranquility.mikutan"
        // 29 = Android 10。MediaStore で Documents 配下に権限なしで書けるのが 29 から。
        // これ未満だと WRITE_EXTERNAL_STORAGE の権限ダイアログが必要になるため引き上げた。
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
}
