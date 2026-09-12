plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.gms.google-services") // NEW: required for Firebase. Add the matching
    // classpath in your project-level (root) build.gradle.kts / settings.gradle.kts:
    //   plugins { id("com.google.gms.google-services") version "4.4.2" apply false }
    // and place google-services.json inside this module's folder (next to this file).
}

android {
    namespace = "com.grocerypos.v11"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.grocerypos.v11"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "11.0-syncfix"
        // Needed to run app/src/androidTest (incl. MigrationTest) with
        // ./gradlew connectedAndroidTest / from Android Studio's test runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Branch identity is configured securely at runtime from Settings > Cloud Sync
        // Setup. Do not bake a branch into the APK; the same APK can now be installed
        // on every device/branch. Server-side Firestore rules remain the authority.
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // FIX (Phase 5 - Stability): added a release signing config — previously there
        // was none, so a `release` build would come out unsigned (can't be installed
        // as an update, or at all on some devices). Credentials are read from Gradle
        // properties instead of being hardcoded, so this file stays safe to commit:
        //   1) Create a real release keystore once:
        //        keytool -genkey -v -keystore release.keystore -alias ibtisaam \
        //          -keyalg RSA -keysize 2048 -validity 10000
        //   2) Put these 4 lines in ~/.gradle/gradle.properties (NOT in this repo):
        //        RELEASE_STORE_FILE=/full/path/to/release.keystore
        //        RELEASE_STORE_PASSWORD=yourStorePassword
        //        RELEASE_KEY_ALIAS=ibtisaam
        //        RELEASE_KEY_PASSWORD=yourKeyPassword
        //   3) Build a release APK/AAB as usual (./gradlew bundleRelease) — Gradle
        //      fills these in automatically. If they're missing, the release build
        //      simply fails with a clear "property not found" error instead of
        //      silently shipping unsigned.
        create("release") {
            storeFile = file(project.findProperty("RELEASE_STORE_FILE") as? String ?: "release.keystore")
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as? String ?: ""
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as? String ?: ""
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as? String ?: ""
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
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

    // ===== Offline-first sync (SyncApi, SyncWorker, SyncRepository) — Firebase =====
    // Retrofit + Gson removed: replaced by Firebase Firestore below.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    // Phone OTP login (LoginActivity) — FirebaseAuth, PhoneAuthCredential,
    // PhoneAuthOptions, PhoneAuthProvider all come from this artifact.
    implementation("com.google.firebase:firebase-auth-ktx")
    // Lets Firestore's Task<T> API be used with Kotlin coroutines (.await()).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // Gson stays — SyncApi still uses it to parse SyncQueueEntry.payloadJson.
    implementation("com.google.code.gson:gson:2.11.0")
    // WorkManager: powers SyncWorker (periodic + immediate background sync).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ===== Unit testing (app/src/test — runs on the JVM, no device/emulator) =====
    testImplementation("junit:junit:4.13.2")

    // ===== Instrumented testing (app/src/androidTest — runs on a device/emulator) =====
    // P3 (Improvement Pack): Room migration tests (MigrationTest.kt) need a real
    // SQLite implementation to create/upgrade a database file, which the plain JVM
    // unit-test sourceset above doesn't have — hence androidTest, not test.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    // room-testing pulls in MigrationTestHelper + the SupportSQLiteOpenHelper APIs
    // MigrationTest.kt uses to hand-build the oldest supported (v13) schema.
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
