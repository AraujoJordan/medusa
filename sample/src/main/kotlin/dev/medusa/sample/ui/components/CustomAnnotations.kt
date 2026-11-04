package dev.medusa.sample.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import dev.medusa.sample.ui.theme.MedusaSampleTheme

/** A custom multipreview: three widths. Medusa needs nothing special, the CLI expands it from the compiled classes. */
@Preview(name = "compact", widthDp = 200, heightDp = 100, showBackground = true)
@Preview(name = "medium", widthDp = 300, heightDp = 100, showBackground = true)
@Preview(name = "expanded", widthDp = 420, heightDp = 100, showBackground = true)
annotation class WidthPreviews

/** Custom multipreview built from a library multipreview plus an own @Preview. */
@PreviewLightDark
@Preview(name = "large text", fontScale = 1.5f, showBackground = true)
annotation class ThemePreviews

/** A multipreview that nests another custom multipreview and adds an own @Preview. */
@WidthPreviews
@Preview(name = "german", locale = "de", showBackground = true)
annotation class AllPreviews

@Composable
private fun Greeting(text: String) = MedusaSampleTheme { Text(text, Modifier.padding(12.dp)) }

@WidthPreviews
@Composable
fun WidthsPreview() = Greeting("custom annotation: three widths")

@ThemePreviews
@Composable
fun ThemesPreview() = Greeting("custom annotation: light, dark, large text")

@AllPreviews
@Composable
fun NestedAnnotationsPreview() = Greeting("nested custom annotations")

/** A custom multipreview combined with a @PreviewParameter provider. */
@WidthPreviews
@Composable
fun WidthsLabelsPreview(@PreviewParameter(ButtonLabelProvider::class) label: String) = Greeting(label)
