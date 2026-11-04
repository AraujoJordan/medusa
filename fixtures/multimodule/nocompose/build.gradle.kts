plugins {
  alias(libs.plugins.android.library)
  id("io.github.araujojordan.medusa")
}

android {
  namespace = "fx.nocompose"
  compileSdk = 36
  defaultConfig {
    minSdk = 24
  }
}


dependencies {
}
