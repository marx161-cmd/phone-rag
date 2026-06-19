plugins {
    id("com.android.application")
}

android {
    namespace = "com.termux.phonerag"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.termux.phonerag"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
}
