package fx.core.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
  MaterialTheme { Text(text, modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
}

@DevicePreviews
@Composable
fun BadgePreview() = Badge("designsystem badge")
