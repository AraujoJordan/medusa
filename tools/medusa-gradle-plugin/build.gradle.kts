plugins {
  id("com.vanniktech.maven.publish")
  kotlin("jvm")
  `java-gradle-plugin`
}


kotlin {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
  implementation(project(":medusa-core"))
  // Only to read the variant source directories; Medusa never requires AGP at runtime (see AgpSources).
  compileOnly("com.android.tools.build:gradle-api:9.0.1")
  testImplementation(kotlin("test"))
  testImplementation(gradleTestKit())
}

gradlePlugin {
  plugins {
    create("medusa") {
      id = "io.github.araujojordan.medusa"
      implementationClass = "dev.medusa.gradle.MedusaPlugin"
      displayName = "Medusa"
      description = "Screenshot tests for Compose previews, rendered by the Android CLI."
    }
  }
}

tasks.test { useJUnitPlatform() }
