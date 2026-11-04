plugins {
  id("com.vanniktech.maven.publish")
  kotlin("jvm")
  kotlin("plugin.serialization")
}


kotlin {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

// The released Paparazzi, only for UpstreamParityTest. Kept out of the test class path on purpose: it contains the
// same class names as the vendored copy, so the test loads it in an isolated class loader.
val upstream: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}

dependencies {
  upstream("app.cash.paparazzi:paparazzi:2.0.0-alpha05.1")
  api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  inputs.files(upstream)
  doFirst { systemProperty("upstream.classpath", upstream.files.joinToString(File.pathSeparator)) }
}

// `benchmark/differs.py` runs this: which differ flags which image, and how fast.
tasks.register<JavaExec>("differMatrix") {
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("dev.medusa.core.compare.DifferMatrixKt")
  args(project.findProperty("corpus")?.toString() ?: "")
}
