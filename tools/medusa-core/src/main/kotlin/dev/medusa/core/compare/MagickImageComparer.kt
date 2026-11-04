package dev.medusa.core.compare

import dev.medusa.core.Comparison
import dev.medusa.core.MedusaException
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Compares with ImageMagick: `magick compare -metric <M> [-fuzz F] golden actual diff.png`.
 *
 * What ImageMagick makes hard, and what is done about it (all measured, see benchmark/SPIKE.md):
 * - the exit code is not reliable (PSNR of identical images exits 1), so only the printed value is read;
 * - images of different size can pass silently, so the size is compared first, from the PNG headers;
 * - `AE` is not an integer pixel count on RGBA images (it is alpha weighted), so the value in parentheses, the fraction of
 *   the image, is used.
 *
 * Pass rule: `AE`, `RMSE`, `MAE`, `DSSIM` are a normalised error, shown as a percentage and compared with
 * [maxPercentDifference] (lower is better); `PSNR` is in decibels and must be at least [minPsnr].
 */
class MagickImageComparer(
  private val binary: File,
  metric: String,
  private val fuzz: String?,
  private val minPsnr: Double,
  private val maxPercentDifference: Double,
  private val workDir: File,
) : ImageComparer {
  private val metric = metric.uppercase()
  private val counter = AtomicLong()

  init {
    if (this.metric !in SUPPORTED) {
      throw MedusaException("Unsupported ImageMagick metric '$metric'. Supported: ${SUPPORTED.joinToString()}")
    }
    workDir.mkdirs()
  }

  override fun compare(golden: File, actual: File): Comparison {
    DeltaImage.sizeChange(golden, actual)?.let { return it }
    val diffFile = File(workDir, "magick-${counter.incrementAndGet()}.png")
    try {
      val reading = parse(run(golden, actual, diffFile), golden)
      return if (metric == "PSNR") {
        val db = reading.value
        if (db >= minPsnr) {
          Comparison.Passed(0f)
        } else {
          DeltaImage.failure(
            (minPsnr - db).toFloat(),
            "magick PSNR %.1f dB is below the minimum of %.1f dB".format(db, minPsnr),
            golden, actual, diffFile,
          )
        }
      } else {
        val percent = (reading.normalized ?: reading.value) * 100.0
        if (percent <= maxPercentDifference) {
          Comparison.Passed(percent.toFloat())
        } else {
          DeltaImage.failure(
            percent.toFloat(),
            "magick $metric: %.3f%% (allowed %.3f%%)".format(percent, maxPercentDifference),
            golden, actual, diffFile,
          )
        }
      }
    } finally {
      diffFile.delete()
    }
  }

  private fun run(golden: File, actual: File, diff: File): String {
    val cmd = buildList {
      add(binary.path)
      if (binary.name != "compare") add("compare") // ImageMagick 7 is `magick compare`, ImageMagick 6 is `compare`
      add("-metric"); add(metric)
      if (fuzz != null) { add("-fuzz"); add(fuzz) }
      add("-highlight-color"); add("red"); add("-lowlight-color"); add("none")
      add(golden.path); add(actual.path); add(diff.path)
    }
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start() // the value is printed on stderr
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw MedusaException("magick did not finish within 60 s for ${golden.name}")
    }
    return output
  }

  internal class Reading(val value: Double, val normalized: Double?)

  private fun parse(output: String, golden: File): Reading {
    for (line in output.lineSequence()) {
      val reading = parseLine(line)
      if (reading != null) return reading
    }
    throw MedusaException("magick printed no $metric value for ${golden.name}. Output was:\n${output.trim()}")
  }

  companion object {
    val SUPPORTED = listOf("AE", "RMSE", "MAE", "DSSIM", "PSNR")

    // Anchored at the start only: for some metrics ImageMagick prints a warning right after the value, on the same line.
    private val VALUE = Regex("""^\s*(inf|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)\s*(?:\(\s*([^)]*?)\s*\))?""")

    /** `1638.38 (0.025)` -> value 1638.38, normalised 0.025. Lines that are not a reading (warnings) give null. */
    internal fun parseLine(line: String): Reading? {
      val m = VALUE.find(line.trim()) ?: return null
      fun number(s: String) = if (s == "inf") Double.POSITIVE_INFINITY else s.toDoubleOrNull()
      val value = number(m.groupValues[1]) ?: return null
      return Reading(value, m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number))
    }

    fun locate(explicit: String? = null, path: String? = System.getenv("PATH")): File =
      BinaryLocator.locate("magick", explicit, path, "brew install imagemagick")
  }
}
