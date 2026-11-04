package dev.medusa.sample.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import dev.medusa.sample.R
import dev.medusa.sample.ui.theme.MedusaSampleTheme

/** Prints what the renderer actually applied, so each @Preview parameter can be verified by eye. */
@Composable
private fun Probe(title: String) {
  val config = LocalConfiguration.current
  val density = LocalDensity.current
  MedusaSampleTheme {
    Column(Modifier.padding(12.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text("screen ${config.screenWidthDp}x${config.screenHeightDp}dp")
      Text("density ${density.density} fontScale ${density.fontScale}")
      Text("locale ${config.locales[0]} night ${config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES}")
      Text("sdk ${android.os.Build.VERSION.SDK_INT} app_name=${stringResource(R.string.app_name)}")
    }
  }
}

@Preview(name = "device pixel 5", device = Devices.PIXEL_5)
@Composable
fun ParamDevicePreview() = Probe("device=PIXEL_5")

@Preview(name = "tablet", device = Devices.PIXEL_TABLET)
@Composable
fun ParamTabletPreview() = Probe("device=PIXEL_TABLET")

@Preview(widthDp = 200, heightDp = 300)
@Composable
fun ParamSizePreview() = Probe("200x300dp")

@Preview(fontScale = 1.8f)
@Composable
fun ParamFontScalePreview() = Probe("fontScale=1.8")

@Preview(locale = "de")
@Composable
fun ParamLocalePreview() = Probe("locale=de")

@Preview(apiLevel = 30)
@Composable
fun ParamApiLevelPreview() = Probe("apiLevel=30")

@Preview(showSystemUi = true)
@Composable
fun ParamSystemUiPreview() = Probe("showSystemUi")

@Preview(showBackground = true, backgroundColor = 0xFFFFE082)
@Composable
fun ParamBackgroundPreview() = Probe("backgroundColor")

@PreviewLightDark
@Composable
fun ParamLightDarkPreview() = Probe("PreviewLightDark")

@PreviewFontScale
@Composable
fun ParamFontScalesPreview() = Probe("PreviewFontScale")

@Preview(name = "rtl", locale = "ar")
@Composable
fun ParamRtlPreview() = Probe("locale=ar (RTL)")
