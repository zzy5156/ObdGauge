plugins {
    id("com.android.application")
}

android {
    namespace = "com.cloudwolf.obdgauge"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.cloudwolf.obdgauge"
        // 覆盖全部比亚迪 DiLink 车机（早期为 Android 5/6，minSdk 过高会报"解析软件包时出现问题"）
        minSdk = 21
        targetSdk = 33
        versionCode = 6
        versionName = "2.0.0"
    }

    signingConfigs {
        // Android 7 以下只认 v1 签名，两种都保留
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // OBD 方案不依赖车机签名，直接使用 debug 签名即可安装；
            // 如需自有发布签名，可通过 -PSTORE_FILE 等 gradle 属性覆盖
            val store = providers.gradleProperty("STORE_FILE").orNull
            signingConfig = if (store != null) {
                signingConfigs.create("release") {
                    storeFile = file(store)
                    storePassword = providers.gradleProperty("STORE_PASSWORD").orNull
                    keyAlias = providers.gradleProperty("KEY_ALIAS").orNull
                    keyPassword = providers.gradleProperty("KEY_PASSWORD").orNull
                    enableV1Signing = true
                    enableV2Signing = true
                }
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
}
