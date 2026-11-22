import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.acam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.acam"
        minSdk = 26
        targetSdk = 35
        // Versioning rule (user-defined, applies from 1.2.0 on):
        //   Every fix/update increments X: A.B.X -> A.B.(X+1).
        //   When X reaches 10: X rolls to 0 and B increments.
        //   When B reaches 10: B rolls to 0 and A increments.
        //   versionCode is simply +1 per release (9 -> 1.2.3 -> 1.2.4 -> 1.2.5).
        versionCode = 12
        versionName = "1.2.6"
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

    // Optional local signing: create keystore.properties (git-ignored) with
    // storeFile/storePassword/keyAlias/keyPassword to sign releases.
    val keystoreProps = rootProject.file("keystore.properties")
    if (keystoreProps.exists()) {
        val props = Properties().apply {
            keystoreProps.inputStream().use { load(it) }
        }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
        buildTypes.getByName("release") {
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose (Material 3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // CameraX with Camera2 interop
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    // PreviewView: the platform rendering path used by the system
    // viewfinder (the last-resort compatibility rung, 1.2.4).
    implementation(libs.androidx.camera.view)

    // Persistence: DataStore + kotlinx.serialization (JSON)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // EXIF writing for saved photos
    implementation(libs.androidx.exifinterface)

    // NOTE: dependency policy for ACam:
    //  - No INTERNET permission, no analytics, no ads, no trackers.
    //  - No ML Kit / Play Services ML / TensorFlow / any on-device AI model.
    //  - No face detection anywhere (Camera2 STATISTICS_FACE_DETECT_MODE stays OFF).
    // The list above is the complete runtime dependency set.

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
