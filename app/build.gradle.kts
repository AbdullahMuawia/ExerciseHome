import java.util.Properties

// Load API Key from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { reader ->
        localProperties.load(reader)
    }
} else {
    println("Warning: local.properties file not found. MAPS_API_KEY will be empty.")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose.compiler)
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

        buildConfigField("String", "MAPS_API_KEY", "\"${localProperties.getProperty("MAPS_API_KEY", "")}\"")
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")

        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3" // Adjust if necessary
    }
} // End of android block


dependencies {
    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    // OSMDroid
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Google Play Services (Location, Maps, Auth for bridge)
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Google Identity Services (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0-alpha01")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0-alpha01")

    // Google API Client & Drive API V3
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20220815-2.0.0")
    // implementation("androidx.activity:activity-ktx:1.8.0") // Duplicate, use 1.9.0 from above
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.35.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Already present for Roads API, also okay for general use

    implementation("com.google.code.gson:gson:2.10.1")

    // Networking (OkHttp for Roads API) - Already declared above

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ExifInterface
    implementation("androidx.exifinterface:exifinterface:1.3.7") // Added this line

    // Jetpack Compose Dependencies
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing Dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}