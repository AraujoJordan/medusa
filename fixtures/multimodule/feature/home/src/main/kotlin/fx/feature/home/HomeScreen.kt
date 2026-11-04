package fx.feature.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import fx.core.designsystem.AllPreviews
import fx.core.designsystem.Badge
import fx.core.designsystem.DevicePreviews
import fx.core.designsystem.ThemePreviews

@Composable
fun HomeScreen() = Badge("home screen")

/** Custom annotation defined in another module. */
@DevicePreviews
@Composable
fun HomePreview() = HomeScreen()

/** Nested custom annotations from another module. */
@ThemePreviews
@Composable
fun HomeThemePreview() = HomeScreen()

@AllPreviews
@Composable
fun HomeAllPreview() = HomeScreen()

@Preview(showBackground = true)
@Composable
fun HomePlainPreview() = Text("plain preview in :feature:home")
