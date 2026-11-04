pluginManagement {
  includeBuild("../../tools")
  repositories {
    google { content { includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*") } }
    mavenCentral()
    gradlePluginPortal()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google { content { includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*") } }
    mavenCentral()
  }
}
rootProject.name = "multimodule"

include(":app", ":feature:home", ":core:designsystem", ":legacy", ":empty", ":nocompose")
// The directory differs from the path: a directory based reference would break here.
project(":legacy").projectDir = file("old/legacy")
