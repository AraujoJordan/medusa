package fx.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import fx.feature.home.HomeScreen

@Composable
fun AppScreen() = HomeScreen()

@Preview(showBackground = true)
@Composable
fun AppPreview() = Text("the app module's own preview")
