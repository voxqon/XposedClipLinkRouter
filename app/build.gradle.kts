import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localSigning = Properties().apply {
    val propertiesFile = rootProject.file("signing.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}
val ciSigningFile = providers.environmentVariable("CLIPLINK_KEYSTORE_FILE").orNull
val releaseVersionName = providers.environmentVariable("CLIPLINK_VERSION_NAME").orNull

android {
    namespace = "com.github.voxqon.xposed.clipLinkrouter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.voxqon.xposed.clipLinkrouter"
        minSdk = 35
        targetSdk = 35
        versionCode = 9
        versionName = releaseVersionName ?: "2.0.2"
    }

    signingConfigs {
        if (ciSigningFile != null || localSigning.isNotEmpty()) {
            create("localRelease") {
                storeFile = rootProject.file(ciSigningFile ?: localSigning.getProperty("storeFile"))
                storePassword = if (ciSigningFile != null) System.getenv("CLIPLINK_STORE_PASSWORD") else localSigning.getProperty("storePassword")
                keyAlias = if (ciSigningFile != null) System.getenv("CLIPLINK_KEY_ALIAS") else localSigning.getProperty("keyAlias")
                keyPassword = if (ciSigningFile != null) System.getenv("CLIPLINK_KEY_PASSWORD") else localSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = providers.gradleProperty("cliplink.minify").orNull != "false"
            isShrinkResources = isMinifyEnabled
            if (ciSigningFile != null || localSigning.isNotEmpty()) signingConfig = signingConfigs.getByName("localRelease")
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
        // Compress DEX in the APK; Android extracts it during installation.
        dex.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.register("verifyReleaseConfiguration") {
    doLast {
        require((android.defaultConfig.versionCode ?: 0) > 0) { "Release versionCode must be a positive integer" }
        require(ciSigningFile != null && listOf("CLIPLINK_STORE_PASSWORD", "CLIPLINK_KEY_ALIAS", "CLIPLINK_KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }) {
            "Release signing configuration is incomplete"
        }
    }
}

dependencies {
    implementation(project(":core"))
    // 由 Xposed 框架提供运行时实现，不能打进 APK。
    compileOnly("io.github.libxposed:api:101.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
