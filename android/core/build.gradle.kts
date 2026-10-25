import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM: no Android dependencies, so everything here is unit-tested on the JVM.
plugins {
  alias(libs.plugins.kotlin.jvm)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  jvmToolchain(25)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  testImplementation(libs.junit)
}
