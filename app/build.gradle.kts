plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") // ✅ Apply Google Services Plugin Here
}

android {
    namespace = "com.example.exercisehome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.exercisehome"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // AndroidX and Material Design
    implementation(libs.androidx.lifecycle.runtime.ktx.v261)
    implementation(libs.androidx.core.ktx.v1120)
    implementation(libs.androidx.appcompat.v161)
    implementation(libs.material.v1110)

    implementation(libs.androidx.material3.v101)


    // OSMDroid Libraries
    implementation(libs.osmdroid.android)


    // GeoPackage Support


    // Play Services
    implementation(libs.play.services.fitness.v2101) // Update to the latest

    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit.v115)
    androidTestImplementation(libs.androidx.espresso.core.v351)
    androidTestImplementation(libs.androidx.ui.test.junit4.v140)
    debugImplementation(libs.androidx.ui.tooling.v140)
    debugImplementation(libs.androidx.ui.test.manifest.v140)
}





dependencies {
    // AndroidX and Material Design
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-compose:1.4.0")

    // OSMDroid Libraries
    implementation("org.osmdroid:osmdroid-android:6.1.8")

    // Google Play Services (Fitness API, Location, etc.)
    implementation("com.google.android.gms:play-services-fitness:21.0.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.12.0")) // ✅ BoM for Firebase
    implementation("com.google.firebase:firebase-analytics") // ✅ Firebase Analytics
    implementation("com.google.firebase:firebase-auth") // ✅ Firebase Authentication
    implementation("com.google.firebase:firebase-firestore") // ✅ Firestore Database

    implementation("com.squareup.okhttp3:okhttp:4.9.1")

    dependencies {
        // Import the BoM for the Firebase platform
        implementation(platform("com.google.firebase:firebase-bom:33.12.0"))

        // Add the dependencies for the App Check libraries
        // When using the BoM, you don't specify versions in Firebase library dependencies
        implementation("com.google.firebase:firebase-appcheck-debug")
    }
}

