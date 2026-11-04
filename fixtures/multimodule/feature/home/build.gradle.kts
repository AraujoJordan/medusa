plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("io.github.araujojordan.medusa")
}

android {
  namespace = "fx.feature.home"
  compileSdk = 36
  defaultConfig {
    minSdk = 24
  }
  buildFeatures { compose = true }
}


dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(project(":core:designsystem"))
}
