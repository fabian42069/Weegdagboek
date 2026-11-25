plugins {
    id("com.android.application")
}

android {
    namespace = "nl.weegdagboek.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.weegdagboek.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.7.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kopieert de webpagina (../weegdagboek-offline.html) bij elke build naar de app.
val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../weegdagboek-offline.html"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "index.html" }
}
tasks.named("preBuild") { dependsOn(copyWeb) }
