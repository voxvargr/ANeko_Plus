import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.foss.wearneko"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Data Layer peers must share an application ID and signing certificate.
        applicationId = "org.nqmgaming.aneko"
        minSdk = 34
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()
    }

    flavorDimensions += "connectivity"
    productFlavors {
        create("foss") { dimension = "connectivity" }
        create("connected") { dimension = "connectivity" }
    }

    val signingPropertiesFile = rootProject.file("key.properties")
    if (signingPropertiesFile.exists()) {
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use { load(it) }
        }
        signingConfigs {
            create("release") {
                storeFile = project(":app").file(signingProperties["storeFile"] as String)
                storePassword = signingProperties["storePassword"] as String
                keyAlias = signingProperties["keyAlias"] as String
                keyPassword = signingProperties["keyPassword"] as String
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":companion"))
    testImplementation(libs.junit)
}
