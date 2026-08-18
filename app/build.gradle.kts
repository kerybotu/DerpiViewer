plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.kerybotu.derpibooru.mirror"
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.kerybotu.derpiviewer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

// Android Studio 的部分运行配置仍会请求旧版 Android Gradle Plugin 的
// `unitTestClasses` 生命周期任务。AGP 9 已改用 variant-specific 任务，
// 保留一个兼容别名可避免 IDE 同步/构建直接因任务不存在而失败。
tasks.register("unitTestClasses") {
    group = "verification"
    description = "Compatibility lifecycle task for local unit-test classes"
    dependsOn(tasks.matching {
        it.name == "compileDebugUnitTestKotlin" ||
            it.name == "compileDebugUnitTestJavaWithJavac" ||
            it.name == "compileDebugUnitTestSources"
    })
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.webkit:webkit:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
}
