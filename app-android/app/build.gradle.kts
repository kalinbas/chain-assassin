plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun envOrDefault(name: String, fallback: String): String {
    val value = System.getenv(name)?.trim()
    return if (value.isNullOrEmpty()) fallback else value
}

fun quote(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

fun envLongOrDefault(name: String, fallback: Long): Long {
    return System.getenv(name)?.trim()?.toLongOrNull() ?: fallback
}

android {
    namespace = "com.cryptohunt.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cryptohunt.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField(
            "String",
            "CHAIN_CONTRACT_ADDRESS",
            quote(envOrDefault("CHAIN_CONTRACT_ADDRESS", "0x6c14a010100cf5e0E1E67DD66ef7BBb3ea8B6D69"))
        )
        buildConfigField(
            "String",
            "CHAIN_RPC_URL",
            quote(envOrDefault("CHAIN_RPC_URL", "https://base-sepolia.g.alchemy.com/v2/gwRYWylWRij2jXTnPXR90v-YqXh96PDX"))
        )
        buildConfigField(
            "String",
            "CHAIN_RPC_WS_URL",
            quote(envOrDefault("CHAIN_RPC_WS_URL", "wss://base-sepolia.g.alchemy.com/v2/gwRYWylWRij2jXTnPXR90v-YqXh96PDX"))
        )
        buildConfigField(
            "long",
            "CHAIN_ID",
            "${envLongOrDefault("CHAIN_ID", 84532L)}L"
        )
        buildConfigField(
            "String",
            "CHAIN_EXPLORER_URL",
            quote(envOrDefault("CHAIN_EXPLORER_URL", "https://sepolia.basescan.org"))
        )
        buildConfigField(
            "String",
            "CHAIN_NAME",
            quote(envOrDefault("CHAIN_NAME", "Base Sepolia"))
        )
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DISCLAIMER"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/NOTICE"
        }
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.56.2")
    kapt("com.google.dagger:hilt-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // CameraX
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Barcode
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // OpenStreetMap (osmdroid) — free, no API key needed
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Location (still using Play Services for GPS)
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Web3j for wallet + blockchain
    implementation("org.web3j:core:4.10.3")

    // OkHttp for WebSocket (also transitive via web3j, but explicit for clarity)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Encrypted key storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.2")

    // Accompanist (permissions)
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Instrumented tests
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

kapt {
    correctErrorTypes = true
}
