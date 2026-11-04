package dev.medusa.sample.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import dev.medusa.sample.ui.theme.MedusaSampleTheme

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
  Button(onClick = onClick, modifier = modifier) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
  OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
}

@Preview(showBackground = true)
@Composable
fun PrimaryButtonPreview() {
  MedusaSampleTheme { PrimaryButton("Primary", Modifier.padding(16.dp)) }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PrimaryButtonDarkPreview() {
  MedusaSampleTheme { PrimaryButton("Primary dark", Modifier.padding(16.dp)) }
}

@Preview(showBackground = true)
@Composable
fun ButtonsColumnPreview() {
  MedusaSampleTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      PrimaryButton("Primary")
      SecondaryButton("Secondary")
    }
  }
}

class ButtonLabelProvider : PreviewParameterProvider<String> {
  override val values = sequenceOf("Short", "A considerably longer label")
}

@Preview(showBackground = true)
@Composable
fun PrimaryButtonLabelsPreview(@PreviewParameter(ButtonLabelProvider::class) label: String) {
  MedusaSampleTheme { PrimaryButton(label, Modifier.padding(16.dp)) }
}
