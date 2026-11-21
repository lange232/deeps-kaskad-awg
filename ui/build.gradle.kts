// Modified for deeps kaskad
@file:Suppress("UnstableApiUsage")

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val pkg: String = providers.gradleProperty("amneziawgPackageName").get()
val appId: String = providers.gradleProperty("amneziawgApplicationId").getOrElse(pkg)

// Signing material lives in the gitignored local.properties, so the keystore and its passwords
// never reach the repository. Without them the release build is left unsigned instead of failing:
// CI and contributors can still compile everything else.
val signingProperties: Properties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
val keystorePath: String? = signingProperties.getProperty("KEY_STORE_PATH")
val keystoreFile: File? = keystorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }

// A build for a specific provider may pre-fill the panel address. The address itself is a local
// setting: it lives in the gitignored local.properties as BRANDED_SERVER and never in the sources,
// so the same build tooling stays public while the deployment stays private.
val brandedServer: String = signingProperties.getProperty("BRANDED_SERVER").orEmpty().trim()
val brandedServerLiteral: String =
    "\"" + brandedServer.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val appVersionName: String = providers.gradleProperty("amneziawgVersionName").get()

/**
 * versionCode is derived from versionName so the two can never disagree:
 * `major * 10 000 + minor * 100 + patch`, then two digits for an optional fourth segment.
 * `0.4.12` → 41200, `0.4.12.1` → 41201, `0.4.13` → 41300, `0.5.0` → 50000.
 */
fun versionCodeOf(name: String): Int {
    val parts = name.split('.')
    require(parts.size in 3..4 && parts.all { it.matches(Regex("0|[1-9][0-9]*")) })
    val values = parts.map { it.toLong() }
    require(values[0] <= 2100 && values.drop(1).all { it <= 99 })
    val code = ((values[0] * 10_000 + values[1] * 100 + values[2]) * 100) + values.getOrElse(3) { 0 }
    require(code in 1..2_100_000_000L)
    return code.toInt()
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    buildFeatures {
        buildConfig = true
        dataBinding = true
        viewBinding = true
    }
    namespace = pkg
    defaultConfig {
        applicationId = appId
        targetSdk = 36
        versionName = appVersionName
        versionCode = versionCodeOf(appVersionName)
        buildConfigField("int", "MIN_SDK_VERSION", minSdk.toString())
        buildConfigField("String", "DEFAULT_SERVER", brandedServerLiteral)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storePassword = signingProperties.getProperty("SIGNING_STORE_PASSWORD")
                keyAlias = signingProperties.getProperty("SIGNING_KEY_ALIAS")
                keyPassword = signingProperties.getProperty("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            keystoreFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-android-optimize.txt")
            packaging {
                resources {
                    excludes += "DebugProbesKt.bin"
                    excludes += "kotlin-tooling-metadata.json"
                    excludes += "META-INF/*.version"
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("googleplay") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "LongLogTag"
        warning += "MissingTranslation"
        warning += "ImpliedQuantity"
    }
}

dependencies {
    implementation(project(":tunnel"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.material)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    coreLibraryDesugaring(libs.desugarJdkLibs)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:unchecked")
    options.isDeprecation = true
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
