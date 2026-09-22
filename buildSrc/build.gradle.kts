plugins {
  `kotlin-dsl`
}

repositories {
  gradlePluginPortal()
}

dependencies {
  implementation("org.json:json:20240303")
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.sigstore.gradle.plugin)
  implementation(libs.maven.publish.gradle.plugin)
  implementation(libs.detekt.gradle.plugin)
  implementation(libs.ktlint.gradle.plugin)
  implementation(libs.kotest.gradle.plugin)
  implementation(libs.ksp.gradle.plugin)
}