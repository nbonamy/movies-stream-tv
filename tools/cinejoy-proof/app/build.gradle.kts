plugins { id("com.android.application"); kotlin("android") }
android {
 namespace = "fr.bonamy.movies.cinejoyproof"
 compileSdk = 36
 defaultConfig { applicationId = "fr.bonamy.movies.cinejoyproof"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "proof" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("com.google.code.gson:gson:2.12.1")
 implementation("androidx.media3:media3-exoplayer:1.11.1")
 implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
 implementation("androidx.media3:media3-ui:1.11.1")
}
