package dev.medusa.core

import app.cash.paparazzi.Differ
import app.cash.paparazzi.internal.differs.DeltaE2000
import app.cash.paparazzi.internal.differs.Flip
import app.cash.paparazzi.internal.differs.Mssim
import app.cash.paparazzi.internal.differs.OffByTwo
import app.cash.paparazzi.internal.differs.PixelPerfect
import app.cash.paparazzi.internal.differs.Sift
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_INT_ARGB
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/** Paparazzi's default `maxPercentDifference` (`detectMaxPercentDifferenceDefault()`). */
const val DEFAULT_MAX_PERCENT_DIFFERENCE = 0.01

sealed interface Comparison {
  val percentDifference: Float

  /** Identical, "similar" (every pixel within the differ's tolerance) or within `maxPercentDifference`. */
  data class Passed(override val percentDifference: Float) : Comparison

  /** [delta] is Paparazzi's image: expected | delta | actual side by side. */
  data class Failed(override val percentDifference: Float, val reason: String, val delta: BufferedImage) : Comparison
}

/** The differs Paparazzi ships, selected by the same names as its `app.cash.paparazzi.differ` property. */
object Differs {
  val names = listOf("offbytwo", "pixelperfect", "mssim", "sift", "flip", "de2000")

  fun byName(name: String?): Differ = when (name?.lowercase()) {
    null, "", "default", "offbytwo" -> OffByTwo
    "pixelperfect" -> PixelPerfect
    "mssim" -> Mssim
    "sift" -> Sift
    "flip" -> Flip
    "de2000" -> DeltaE2000
    else -> throw MedusaException("Unknown differ '$name'. Use one of: ${names.joinToString()}")
  }
}

/**
 * Paparazzi's verdict on two images: `ImageUtils.compareImages` + `ImageUtils.assertImageSimilar`
 * (paparazzi/src/main/java/app/cash/paparazzi/internal/ImageUtils.kt) without the throwing, printing and
 * APNG/label drawing. A comparison fails if the differ reports more than [maxPercentDifference] percent,
 * or if the width or height differ by 2px or more.
 */
object PaparazziComparison {
  fun compare(
    golden: BufferedImage,
    actual: BufferedImage,
    differ: Differ = OffByTwo,
    maxPercentDifference: Double = DEFAULT_MAX_PERCENT_DIFFERENCE,
  ): Comparison {
    // Same normalization as upstream compareImages: the golden is redrawn as ARGB if it is not.
    var expected = golden
    if (expected.type != TYPE_INT_ARGB) {
      val temp = BufferedImage(expected.width, expected.height, TYPE_INT_ARGB)
      temp.graphics.drawImage(expected, 0, 0, null)
      expected = temp
    }

    val (delta, percentDifference) = when (val result = differ.compare(expected, actual)) {
      is Differ.DiffResult.Identical -> result.delta to 0f
      is Differ.DiffResult.Similar -> result.delta to 0f
      is Differ.DiffResult.Different -> result.delta to result.percentDifference
    }

    val reason = when {
      percentDifference > maxPercentDifference -> "Images differ (by %f%%)".format(percentDifference)
      abs(golden.width - actual.width) >= 2 ->
        "Widths differ too much: ${golden.width}x${golden.height} vs ${actual.width}x${actual.height}"
      abs(golden.height - actual.height) >= 2 ->
        "Heights differ too much: ${golden.width}x${golden.height} vs ${actual.width}x${actual.height}"
      else -> null
    }
    return if (reason == null) Comparison.Passed(percentDifference) else Comparison.Failed(percentDifference, reason, delta)
  }

  fun compare(golden: File, actual: File, differ: Differ = OffByTwo, maxPercentDifference: Double = DEFAULT_MAX_PERCENT_DIFFERENCE) =
    compare(Images.read(golden), Images.read(actual), differ, maxPercentDifference)
}

object Images {
  fun read(file: File): BufferedImage =
    ImageIO.read(file) ?: throw MedusaException("${file.path} is not a readable image")

  fun write(image: BufferedImage, file: File) {
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
  }
}
