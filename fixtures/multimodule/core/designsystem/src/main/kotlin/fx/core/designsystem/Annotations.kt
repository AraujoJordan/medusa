package fx.core.designsystem

import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark

/** Custom multipreview: three screen sizes. */
@Preview(name = "phone", device = Devices.PIXEL_5, showBackground = true)
@Preview(name = "tablet", device = Devices.PIXEL_TABLET, showBackground = true)
@Preview(name = "small", widthDp = 200, heightDp = 300, showBackground = true)
annotation class DevicePreviews

/** Custom multipreview built from two library multipreviews: light/dark x font scales. */
@PreviewLightDark
@PreviewFontScale
annotation class ThemePreviews

/** A multipreview that nests another custom multipreview and adds an own @Preview. */
@DevicePreviews
@Preview(name = "german", locale = "de", showBackground = true)
annotation class AllPreviews
