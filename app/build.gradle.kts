plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.gemmaapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gemmaapp"
        minSdk = 27          // Vulkan 1.1 baseline
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // arm64-v8a: all modern Android phones
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Vulkan GPU backend — the primary acceleration path on Android
                arguments += listOf(
                    "-DGGML_VULKAN=OFF",    // Vulkan requires host Vulkan SDK (vulkan.hpp); use NEON CPU instead
                    "-DGGML_OPENMP=OFF",    // avoid omp overhead on mobile
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.activity.ktx)
    implementation(libs.onnxruntime.android)   // Whisper ONNX inference
    implementation("${libs.jna.get().module}:${libs.jna.get().version}@aar")
    implementation("${libs.vosk.android.get().module}:${libs.vosk.android.get().version}@aar")
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
