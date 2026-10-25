import com.android.build.api.artifact.SingleArtifact

plugins {
  // AGP 9 compiles Kotlin itself (built-in Kotlin); org.jetbrains.kotlin.android is not applied.
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.wifishuriken.companion"
  compileSdk {
    version = release(37) { minorApiLevel = 2 }
  }

  defaultConfig {
    applicationId = "com.wifishuriken.companion"
    minSdk = 33
    targetSdk = 37
    versionCode = 2
    versionName = "0.2.0"
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

  buildFeatures {
    viewBinding = true
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

dependencies {
  implementation(project(":core"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.runtime)
  // Prototype UI, removed in the Compose rewrite.
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

  testImplementation(libs.junit)
}

// The app must never be able to reach the network: fail the build if a library's manifest
// merges a network permission in (MapLibre's does; the app manifest removes it).
abstract class VerifyNoNetworkPermission : DefaultTask() {
  @get:InputFile
  abstract val mergedManifest: RegularFileProperty

  @TaskAction
  fun verify() {
    val text = mergedManifest.get().asFile.readText()
    val banned = listOf(
      "android.permission.INTERNET",
      "android.permission.ACCESS_NETWORK_STATE",
      "android.permission.ACCESS_WIFI_STATE",
    ).filter { text.contains("\"$it\"") }
    if (banned.isNotEmpty()) {
      throw GradleException("Merged manifest requests forbidden network permissions: $banned")
    }
  }
}

val verifyAll = tasks.register("verifyNoNetworkPermission")

androidComponents {
  onVariants { variant ->
    val name = variant.name.replaceFirstChar { it.uppercase() }
    val perVariant = tasks.register<VerifyNoNetworkPermission>("verifyNoNetworkPermission$name") {
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
    verifyAll.configure { dependsOn(perVariant) }
  }
}

tasks.named("check") { dependsOn(verifyAll) }
