package dev.medusa.sample.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.medusa.sample.ui.theme.MedusaSampleTheme

@Composable
fun ProfileCard(
  name: String,
  handle: String,
  bio: String,
  stats: List<Pair<String, String>>,
  modifier: Modifier = Modifier,
) {
  ElevatedCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            name.split(" ").map { it.first() }.joinToString(""),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleLarge,
          )
        }
        Column(Modifier.padding(start = 16.dp)) {
          Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
      }
      Spacer(Modifier.height(16.dp))
      Text(bio, style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(16.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        stats.forEach { (value, label) ->
          Column {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
          }
        }
      }
      Spacer(Modifier.height(20.dp))
      PrimaryButton("Follow", Modifier.fillMaxWidth())
    }
  }
}

private val sampleStats = listOf("128" to "previews", "0" to "diffs", "3" to "modules")

@Composable
private fun SampleProfileCard(darkTheme: Boolean) {
  MedusaSampleTheme(darkTheme = darkTheme) {
    Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
      ProfileCard("Ada Lovelace", "@ada", "Writes @Previews, ships screenshot tests without writing a single test.", sampleStats)
    }
  }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
fun ProfileCardLightPreview() = SampleProfileCard(darkTheme = false)

@Preview(widthDp = 360, showBackground = true)
@Composable
fun ProfileCardDarkPreview() = SampleProfileCard(darkTheme = true)
