import java.net.URI
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
    buildConfig = true
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.all {
      // Robolectric reaches into JDK internals that JDK 25 no longer opens by default.
      it.jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
      )
    }
  }
}

dependencies {
  implementation(project(":core"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.runtime)
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)
  // Prototype UI, removed in the Compose rewrite.
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
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

// Vendor names ship inside the app (no lookups at run time): the IEEE MA-L registry is downloaded at
// build time (cached under build/oui) and reduced to "AABBCC<TAB>Vendor" lines.
abstract class GenerateOuiAsset : DefaultTask() {
  @get:Internal
  abstract val cacheFile: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val cache = cacheFile.get().asFile
    if (!cache.exists() || cache.length() == 0L) {
      try {
        cache.parentFile.mkdirs()
        val conn = URI("https://standards-oui.ieee.org/oui/oui.csv").toURL().openConnection()
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) ShurikenCompanionBuild")
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        val tmp = File(cache.path + ".part")
        conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(cache)
      } catch (e: Exception) {
        logger.warn("OUI registry download failed (${e.message}); vendor names will be empty")
      }
    }
    val out = outputDir.get().asFile.resolve("oui")
    out.mkdirs()
    val lines = if (cache.exists()) cache.readLines().drop(1).mapNotNull(::toAssetLine) else emptyList()
    out.resolve("oui.tsv").writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
  }

  private fun toAssetLine(csvLine: String): String? {
    val fields = ArrayList<String>()
    val cur = StringBuilder()
    var quoted = false
    for (c in csvLine) {
      when {
        c == '"' -> quoted = !quoted
        c == ',' && !quoted -> { fields.add(cur.toString()); cur.setLength(0) }
        else -> cur.append(c)
      }
    }
    fields.add(cur.toString())
    if (fields.size < 3 || fields[0] != "MA-L") return null
    val prefix = fields[1].trim().uppercase()
    val name = fields[2].replace('\t', ' ').trim()
    return if (prefix.length == 6 && name.isNotEmpty()) "$prefix\t$name" else null
  }
}

val generateOuiAsset = tasks.register<GenerateOuiAsset>("generateOuiAsset") {
  cacheFile.set(layout.buildDirectory.file("oui/oui.csv"))
  outputDir.set(layout.buildDirectory.dir("generated/ouiAssets"))
}

val verifyAll = tasks.register("verifyNoNetworkPermission")

androidComponents {
  onVariants { variant ->
    val name = variant.name.replaceFirstChar { it.uppercase() }
    val perVariant = tasks.register<VerifyNoNetworkPermission>("verifyNoNetworkPermission$name") {
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
    verifyAll.configure { dependsOn(perVariant) }
    variant.sources.assets?.addGeneratedSourceDirectory(generateOuiAsset, GenerateOuiAsset::outputDir)
  }
}

tasks.named("check") { dependsOn(verifyAll) }
