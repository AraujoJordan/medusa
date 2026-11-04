plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("io.github.araujojordan.medusa")
}

android {
  namespace = "fx.app"
  compileSdk = 36
  defaultConfig {
    minSdk = 24
    applicationId = "fx.app"
    targetSdk = 37
    versionCode = 1
  }
  buildFeatures { compose = true }
}


dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(project(":feature:home"))
  implementation(project(":core:designsystem"))
}
