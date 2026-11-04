import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  kotlin("jvm") version "2.3.20" apply false
  kotlin("plugin.serialization") version "2.3.20" apply false
  id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

// Publishing, shared by medusa-core and medusa-gradle-plugin.
val onJitpack = System.getenv("JITPACK") == "true"

subprojects {
  // Maven Central wants a namespace you can verify (io.github.<user>); JitPack wants com.github.<user>.<repo>.
  group = if (onJitpack) "com.github.AraujoJordan.medusa" else "io.github.araujojordan"
  version = providers.gradleProperty("medusaVersion").getOrElse("0.1.0")

  plugins.withId("com.vanniktech.maven.publish") {
    extensions.configure<MavenPublishBaseExtension> {
      publishToMavenCentral()
      // Signing needs a key; without one (local builds, JitPack, pull requests) artifacts are published unsigned.
      val hasKey = providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent
      if (hasKey) signAllPublications()

      coordinates(group.toString(), project.name, version.toString())

      pom {
        name = "Medusa ${project.name.removePrefix("medusa-")}"
        description = "Screenshot tests for Jetpack Compose previews, rendered by the Android CLI and compared with odiff, " +
          "Paparazzi's differs or ImageMagick."
        inceptionYear = "2026"
        url = "https://github.com/AraujoJordan/medusa"
        licenses {
          license {
            name = "The Apache License, Version 2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            distribution = "repo"
          }
        }
        developers {
          developer {
            id = "AraujoJordan"
            name = "Jordan Araujo"
            url = "https://github.com/AraujoJordan"
          }
        }
        scm {
          url = "https://github.com/AraujoJordan/medusa"
          connection = "scm:git:git://github.com/AraujoJordan/medusa.git"
          developerConnection = "scm:git:ssh://git@github.com/AraujoJordan/medusa.git"
        }
      }
    }
  }
}
