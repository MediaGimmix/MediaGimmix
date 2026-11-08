plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Companion app: controls the main player on another device through its web control API (WebControl.kt).
android {
    namespace = "com.example.mediaplayer.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mediaplayer.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}

// App icon = app/src/main/assets/icons/app_icon.png (white outline of the logo), else the startup splash image
// (splash.png); copied in at every build, so replacing the file changes the icon. Transparent background.
val launcherArtRes = layout.buildDirectory.dir("generated/launcherIcon/res")
val copyLauncherArt by tasks.registering(Copy::class) {
    val icons = rootProject.file("app/src/main/assets/icons")
    val art = File(icons, "app_icon.png").takeIf { it.exists() } ?: File(icons, "splash.png")
    doFirst {
        if (!art.exists()) throw GradleException("$icons has neither app_icon.png nor splash.png: one of them is the app icon.")
    }
    from(art)
    into(launcherArtRes.map { it.dir("drawable-nodpi") })
    rename { "launcher_art.png" }
}
android.sourceSets.getByName("main").res.srcDir(launcherArtRes)
tasks.named("preBuild") { dependsOn(copyLauncherArt) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
