import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/** Optional generated asset: uses AGP's output metadata, not an arbitrary APK left in build/. */
abstract class BundleWatchApkTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val expectedVariant: Property<String>

    @get:Input
    abstract val expectedVersionCode: Property<Int>

    @TaskAction
    fun bundle() {
        val artifacts = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: error("Wear APK metadata is missing")
        require(artifacts.applicationId == "org.nqmgaming.aneko" &&
            artifacts.variantName == expectedVariant.get()) { "Wear APK variant does not match the phone" }
        val artifact = artifacts.elements.singleOrNull()
            ?: error("Watch setup requires one standalone Wear APK")
        require(artifact.versionCode == expectedVersionCode.get()) { "Wear APK version does not match the phone" }
        val source = File(artifact.outputFile)
        require(source.isFile && source.length() in 1..(64L * 1024 * 1024) &&
            !source.name.endsWith("-unsigned.apk")) { "Watch setup requires a nonempty signed Wear APK" }
        val directory = outputDirectory.get().asFile.resolve("watch").apply { mkdirs() }
        source.copyTo(directory.resolve("ANeko-watch.apk"), overwrite = true)
    }
}

android {
    namespace = "org.nqmgaming.aneko"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.nqmgaming.aneko"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "connectivity"
    productFlavors {
        create("foss") { dimension = "connectivity" }
        create("connected") { dimension = "connectivity" }
    }

    // Load signing config from key.properties (CI/CD)
    val keyPropertiesFile = rootProject.file("key.properties")
    val useReleaseKeystore = keyPropertiesFile.exists()

    if (useReleaseKeystore) {
        val keyProperties = Properties().apply {
            load(keyPropertiesFile.inputStream())
        }
        signingConfigs {
            create("release") {
                storeFile = file(keyProperties["storeFile"] as String)
                storePassword = keyProperties["storePassword"] as String
                keyAlias = keyProperties["keyAlias"] as String
                keyPassword = keyProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (useReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            freeCompilerArgs = listOf("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Keep it for FDroid deployment
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

}

// Opt in for distribution builds. Normal phone-only and all FOSS builds do not build Wear.
if (providers.gradleProperty("bundleWatchApk").map(String::toBoolean).getOrElse(false)) {
    androidComponents.onVariants(androidComponents.selector().withFlavor("connectivity" to "connected")) { variant ->
        val buildType = requireNotNull(variant.buildType)
        val capitalizedVariant = variant.name.replaceFirstChar(Char::uppercaseChar)
        val bundle = tasks.register<BundleWatchApkTask>("bundle${capitalizedVariant}WatchApk") {
            dependsOn(":wear:assemble$capitalizedVariant")
            apkDirectory.set(rootProject.layout.projectDirectory.dir("wear/build/outputs/apk/connected/$buildType"))
            outputDirectory.set(layout.buildDirectory.dir("generated/watch-apk/${variant.name}"))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            expectedVariant.set(variant.name)
            expectedVersionCode.set(libs.versions.versionCode.get().toInt())
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleWatchApkTask::outputDirectory)
    }
}

dependencies {
    implementation(project(":companion"))

    // AndroidX Core
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Compose UI
    implementation(libs.ui)
    implementation(libs.material3)
    implementation(libs.material)
    implementation(libs.ui.tooling.preview)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.animation.graphics)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.ui.graphics)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Coil
    implementation(libs.coil.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    // Ktor
    implementation(libs.bundles.ktor)
    implementation(libs.bundles.navigation)

    // Serialization
    implementation(libs.bundles.serialization)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Kotlin
    implementation(libs.kotlin.stdlib.jdk8)

    // Logging & Debugging
    implementation(libs.slf4j.android)
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

}
