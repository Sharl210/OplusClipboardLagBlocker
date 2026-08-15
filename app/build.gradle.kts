plugins {
    id("com.android.application")
}

android {
    namespace = "com.harl.oplusclipboardlagblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.harl.oplusclipboardlagblocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
