plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        externalNativeBuild {
            cmake {
                cFlags("-std=c11")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
