plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.eldroid.facelock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eldroid.facelock"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // res/ cannot nest the way java/ does - AAPT requires files to sit directly
    // inside layout/, values/ and so on. Declaring several resource roots gets
    // the same grouping: each folder below is its own res root with its own
    // layout/ inside, mirroring the package layout under java/.
    // Listing srcDirs replaces the default "src/main/res", so every root that
    // should be compiled has to appear here.
    sourceSets {
        getByName("main") {
            // setSrcDirs replaces; srcDirs() would append to the default
            // "src/main/res" and leave it as a parent of these, which AGP
            // flags as nested resources and will reject outright in v9.
            res.setSrcDirs(
                listOf(
                    "src/main/res/core",      // design system: values, colours, drawables, menus
                    "src/main/res/auth",      // ui/auth
                    "src/main/res/admin",     // ui/admin
                    "src/main/res/user",      // ui/user
                    "src/main/res/adapter",   // ui/adapter row layouts
                    "src/main/res/common"     // shared includes
                )
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    // Realtime Database: what the ESP32 writes to directly, no Cloud Functions needed.
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")

    // CameraX (face enrollment capture)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // CameraX returns ListenableFuture in its public API but does not expose
    // Guava transitively, so the compile classpath needs it explicitly.
    implementation("com.google.guava:guava:31.1-android")

    // ML Kit face detection (on-device enrollment validation)
    implementation("com.google.mlkit:face-detection:16.1.6")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
