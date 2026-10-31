plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "ge.momento.booth"; compileSdk = 35
 defaultConfig { applicationId = "ge.momento.booth.goprowififix.v14"; minSdk = 26; targetSdk = 35; versionCode = 14; versionName = "1.4-auto-sms" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}

// Media3 runs entirely on the Android device (no external cloud service).
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
}
