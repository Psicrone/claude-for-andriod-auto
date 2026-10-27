plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claudeauto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claudeauto"
        minSdk = 29
        targetSdk = 35
        // Release builds get these from the tag and run number (see .github/workflows/release.yml).
        versionCode = System.getenv("CLAUDE_AUTO_VERSION_CODE")?.toIntOrNull() ?: 3
        versionName = System.getenv("CLAUDE_AUTO_VERSION") ?: "0.3.0"
    }

    // Release signing comes from CI secrets. Without them a release build is signed with the
    // debug key, which installs fine but can't update an APK signed with the real key.
    val keystore = System.getenv("CLAUDE_AUTO_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("CLAUDE_AUTO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLAUDE_AUTO_KEY_ALIAS")
                keyPassword = System.getenv("CLAUDE_AUTO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            // The Anthropic Java SDK and its Jackson/Kotlin dependencies ship overlapping metadata.
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.transition)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
}
