package dev.medusa.core.compare

import dev.medusa.core.Comparison
import dev.medusa.core.Images
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.max

internal object DeltaImage {
  /**
   * Paparazzi's failure image for any differ: expected, delta and actual side by side. [middle] is what the external
   * tool drew (or null when there is none, for a size change), so the report looks the same whichever differ was used.
   */
  fun compose(expected: BufferedImage, middle: BufferedImage?, actual: BufferedImage): BufferedImage {
    val maxWidth = max(expected.width, actual.width)
    val out = BufferedImage(expected.width + maxWidth + actual.width, max(expected.height, actual.height), BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(expected, 0, 0, null)
    if (middle != null) g.drawImage(middle, expected.width, 0, null)
    g.drawImage(actual, expected.width + maxWidth, 0, null)
    g.dispose()
    return out
  }

  fun failure(percent: Float, reason: String, golden: File, actual: File, toolDiff: File?): Comparison.Failed {
    val diff = toolDiff?.takeIf { it.isFile && it.length() > 0 }?.let { runCatching { Images.read(it) }.getOrNull() }
    return Comparison.Failed(percent, reason, compose(Images.read(golden), diff, Images.read(actual)))
  }

  /** External tools cannot be trusted with a size change (ImageMagick silently passes it), so it is decided here. */
  fun sizeChange(golden: File, actual: File): Comparison.Failed? {
    val g = PngHeader.size(golden)
    val a = PngHeader.size(actual)
    if (g == a) return null
    return failure(
      100f,
      "size changed from ${g.first}x${g.second} to ${a.first}x${a.second}",
      golden,
      actual,
      null,
    )
  }
}
