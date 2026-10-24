plugins {
    id("com.android.library")
}

android {
    namespace = "org.nqmgaming.aneko.companion"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    flavorDimensions += "connectivity"
    productFlavors {
        create("foss") { dimension = "connectivity" }
        create("connected") { dimension = "connectivity" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    "connectedImplementation"("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
}
