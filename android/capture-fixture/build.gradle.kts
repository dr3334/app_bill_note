plugins { id("com.android.application") }
android {
    namespace = "dev.inkledger.fixture"
    compileSdk = 35
    defaultConfig {
        // Deliberately exercises the production package allowlist on a disposable emulator.
        // This is NOT Alipay. Never install this fixture on a personal phone.
        applicationId = "com.eg.android.AlipayGphone"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "fixture-only"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
