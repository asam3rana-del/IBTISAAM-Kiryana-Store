plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.grocerypos.v11"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.grocerypos.v11"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "10.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Free, on-device OCR for the "Scan Bill" purchase feature — no API key, no
    // internet required, no per-request cost.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // ML Kit Document Scanner — powers the "Scan Document" button in BillScanActivity.
    // Auto-detects the bill's edges, corrects perspective/skew, and cleans up
    // shadows/glare before OCR runs, which meaningfully improves item/qty/rate read
    // accuracy over a plain camera photo. Also on-device, free, and brings its own
    // built-in camera flow (no CAMERA permission needed).
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Needed for GoogleApiAvailability / ConnectionResult (the Play Services check
    // before launching the Document Scanner).
    implementation("com.google.android.gms:play-services-base:18.5.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.biometric:biometric:1.1.0")

    // ===== Offline-first sync (SyncApi, SyncWorker, SyncRepository) =====
    // Retrofit + Gson: powers SyncEndpoints (push/pull to your backend server).
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    // WorkManager: powers SyncWorker (periodic + immediate background sync).
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
