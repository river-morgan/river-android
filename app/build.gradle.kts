plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ee.river.android"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "ee.river.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"

        val riverEndpoint = System.getenv("RIVER_ANDROID_ENDPOINT") ?: ""
        val riverToken = System.getenv("RIVER_ANDROID_TOKEN") ?: ""
        buildConfigField("String", "RIVER_ENDPOINT", "\"${riverEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "RIVER_TOKEN", "\"${riverToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    val riverUploadKeystore = System.getenv("RIVER_UPLOAD_KEYSTORE")
    val riverUploadStorePassword = System.getenv("RIVER_UPLOAD_STORE_PASSWORD")
    val riverUploadKeyAlias = System.getenv("RIVER_UPLOAD_KEY_ALIAS")
    val riverUploadKeyPassword = System.getenv("RIVER_UPLOAD_KEY_PASSWORD")

    if (!riverUploadKeystore.isNullOrBlank() &&
        !riverUploadStorePassword.isNullOrBlank() &&
        !riverUploadKeyAlias.isNullOrBlank() &&
        !riverUploadKeyPassword.isNullOrBlank()
    ) {
        signingConfigs {
            create("release") {
                storeFile = file(riverUploadKeystore)
                storePassword = riverUploadStorePassword
                keyAlias = riverUploadKeyAlias
                keyPassword = riverUploadKeyPassword
            }
        }

        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
                isMinifyEnabled = false
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-google-shortcuts:1.1.0")
}
