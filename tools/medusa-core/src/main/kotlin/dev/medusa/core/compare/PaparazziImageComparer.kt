package dev.medusa.core.compare

import app.cash.paparazzi.Differ
import dev.medusa.core.Comparison
import dev.medusa.core.Images
import dev.medusa.core.PaparazziComparison
import java.io.File

/** An in-process [Differ] (Paparazzi's, or [YiqDiffer]) judged by Paparazzi's own pass/fail rule. */
class PaparazziImageComparer(
  private val differ: Differ,
  private val maxPercentDifference: Double,
) : ImageComparer {
  override fun compare(golden: File, actual: File): Comparison {
    val expected = Images.read(golden)
    if (java.nio.file.Files.mismatch(golden.toPath(), actual.toPath()) == -1L) return Comparison.Passed(0f)
    return PaparazziComparison.compare(expected, Images.read(actual), differ, maxPercentDifference)
  }
}
