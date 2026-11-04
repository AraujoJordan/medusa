pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
  }
}

rootProject.name = "medusa-tools"

include(":medusa-core")
include(":medusa-gradle-plugin")
