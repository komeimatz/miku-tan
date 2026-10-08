import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 配布用（release）の署名鍵の場所とパスワード。リポジトリ直下の keystore.properties に書く。
// 鍵もこのファイルも Git には入れない（.gitignore 済み）。無ければ release は署名なしで作られる。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
