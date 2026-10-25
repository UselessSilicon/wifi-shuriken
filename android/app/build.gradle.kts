import java.security.MessageDigest
import java.util.zip.GZIPInputStream
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
    versionCode = 3
    versionName = "0.3.0"
  }

  // Release signing comes only from the builder's own ~/.gradle/gradle.properties (never committed):
  // companionStoreFile, companionStorePassword, companionKeyAlias, companionKeyPassword.
  // Without them the release APK is left unsigned (CI). Sign with the key of the installed app so a
  // release updates it in place; a different key would force an uninstall and lose the app's data.
  val releaseStore = providers.gradleProperty("companionStoreFile").orNull
  signingConfigs {
    if (releaseStore != null) {
      create("owner") {
        storeFile = file(releaseStore)
        storePassword = providers.gradleProperty("companionStorePassword").get()
        keyAlias = providers.gradleProperty("companionKeyAlias").get()
        keyPassword = providers.gradleProperty("companionKeyPassword").get()
      }
    }
  }

  buildTypes {
    release {
      // Not debuggable; no code shrinking (MapLibre and Room rely on reflection, and size is not a concern here).
      isMinifyEnabled = false
      signingConfigs.findByName("owner")?.let { signingConfig = it }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
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

// Room writes each schema version here; committed so future versions can be migrated and tested.
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
  implementation(project(":core"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.runtime)
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.core)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  debugImplementation(libs.compose.ui.tooling)
  debugImplementation(libs.compose.ui.test.manifest)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.maplibre.android)
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(platform(libs.compose.bom))
  testImplementation(libs.compose.ui.test.junit4)
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

// Map fonts and sprites ship inside the app (the map never fetches anything): the pinned
// protomaps/basemaps-assets archive is downloaded at build time, verified by SHA-256, and only the
// fonts the styles use plus the v4 sprites are extracted.
abstract class FetchMapAssets : DefaultTask() {
  @get:Input abstract val url: Property<String>
  @get:Input abstract val sha256: Property<String>
  @get:Input abstract val prefixes: ListProperty<String>
  @get:Internal abstract val cacheFile: RegularFileProperty
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun fetch() {
    val cache = cacheFile.get().asFile
    if (!cache.exists() || sha256Of(cache) != sha256.get()) {
      cache.parentFile.mkdirs()
      try {
        val conn = URI(url.get()).toURL().openConnection()
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.getInputStream().use { input -> cache.outputStream().use { input.copyTo(it) } }
      } catch (e: Exception) {
        throw GradleException("Map assets are not cached and could not be downloaded (${e.message})")
      }
      val actual = sha256Of(cache)
      if (actual != sha256.get()) {
        cache.delete()
        throw GradleException("Map assets checksum mismatch: expected ${sha256.get()}, got $actual")
      }
    }
    val out = outputDir.get().asFile.resolve("map")
    out.deleteRecursively()
    GZIPInputStream(cache.inputStream().buffered()).use { tar ->
      val header = ByteArray(512)
      while (tar.readNBytes(header, 0, 512) == 512 && header.any { it != 0.toByte() }) {
        val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
        val prefix = String(header, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
        val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').ifEmpty { "0" }.toLong(8)
        val type = header[156].toInt().toChar()
        val full = if (prefix.isEmpty()) name else "$prefix/$name"
        val rel = full.substringAfter('/')
        val data = tar.readNBytes(size.toInt())
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0) tar.readNBytes(pad)
        val regular = type == '0' || type == '\u0000'
        if (!regular || rel.split('/').any { it == ".." } || prefixes.get().none { rel.startsWith(it) }) continue
        val target = out.resolve(rel)
        target.parentFile.mkdirs()
        target.writeBytes(data)
      }
    }
  }

  private fun sha256Of(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}

val fetchMapAssets = tasks.register<FetchMapAssets>("fetchMapAssets") {
  url.set("https://github.com/protomaps/basemaps-assets/archive/028c18f713baecad011301ff7a69acc39bcc2ae7.tar.gz")
  sha256.set("57e40e8c512bd8042d0a3a251f19d0d1c8523ad963c666c3c6643bada4dc92d0")
  prefixes.set(listOf("fonts/Noto Sans Regular/", "fonts/Noto Sans Medium/", "fonts/Noto Sans Italic/", "sprites/v4/"))
  cacheFile.set(layout.buildDirectory.file("mapassets/basemaps-assets.tar.gz"))
  outputDir.set(layout.buildDirectory.dir("generated/mapAssets"))
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
    variant.sources.assets?.addGeneratedSourceDirectory(fetchMapAssets, FetchMapAssets::outputDir)
  }
}

tasks.named("check") { dependsOn(verifyAll) }
