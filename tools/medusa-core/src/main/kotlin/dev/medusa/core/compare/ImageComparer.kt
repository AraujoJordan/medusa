package dev.medusa.core.compare

import dev.medusa.core.Comparison
import dev.medusa.core.DEFAULT_MAX_PERCENT_DIFFERENCE
import java.io.File
import java.io.Serializable

/**
 * Decides whether a new rendering still matches its golden. Implementations: Paparazzi's differs and the in-process
 * `yiq` differ ([PaparazziImageComparer]), and the external tools [OdiffImageComparer] and [MagickImageComparer].
 *
 * Must be safe to call from several threads at once (the pipeline compares many images concurrently).
 */
interface ImageComparer : AutoCloseable {
  val environment: Map<String, String> get() = mapOf("effectiveComparer" to javaClass.simpleName)
  fun compare(golden: File, actual: File): Comparison

  /** Stops helper processes (the `odiff --server` pool). */
  override fun close() {}
}

/** Everything needed to build the [ImageComparer] a build asked for. Serializable so Gradle can cache it. */
data class ComparerSpec(
  /** `odiff` (default), `yiq`, `magick`, or Paparazzi's `offbytwo`, `pixelperfect`, `mssim`, `sift`, `flip`, `de2000`. */
  val differ: String = "odiff",
  /** Share of pixels (0-100) allowed to differ. What a "difference" is depends on the differ, see the README table. */
  val maxPercentDifference: Double = DEFAULT_MAX_PERCENT_DIFFERENCE,
  val yiq: YiqOptions = YiqOptions(),
  val odiff: OdiffOptions = OdiffOptions(),
  val magick: MagickOptions = MagickOptions(),
  /** Download odiff (pinned version, checksum verified) into [installDir] when it is selected but not found. */
  val autoInstall: Boolean = true,
  /** When odiff is neither found nor installable, use the in-process `yiq` differ (same pixel counts) and warn, instead of failing. */
  val fallbackToYiq: Boolean = true,
  /** Where Medusa installs tools, default `~/.medusa/bin`. */
  val installDir: String? = null,
  val comparisonTimeoutSeconds: Long = 60,
) : Serializable

data class YiqOptions(
  /** 0..1, odiff's and pixelmatch's default is 0.1. 0 flags any colour change. */
  val threshold: Double = 0.1,
  /** Do not count antialiased pixels. */
  val antialiasing: Boolean = false,
) : Serializable

data class OdiffOptions(
  /** Path or name of the `odiff` binary; default: looked up on PATH. */
  val binary: String? = null,
  val threshold: Double = 0.1,
  val antialiasing: Boolean = false,
  /** Rectangles to skip, `x1:y1-x2:y2`. */
  val ignoreRegions: List<String> = emptyList(),
  /** Talk to a pool of long-running `odiff --server` processes (about 1 ms per image) instead of one process per image (about 6 ms). */
  val useServer: Boolean = true,
) : Serializable

data class MagickOptions(
  /** Path or name of the `magick` binary (or an ImageMagick 6 `compare`); default: looked up on PATH. */
  val binary: String? = null,
  /** `AE` (pixels over `fuzz`), `RMSE`, `MAE`, `DSSIM` (normalised value in percent, lower is better) or `PSNR` (decibels, higher is better). */
  val metric: String = "AE",
  /** `-fuzz`: colour distance below which two pixels count as equal, for example `2%`. */
  val fuzz: String? = "2%",
  /** For `PSNR`: the lowest decibel value that still passes. */
  val minPsnr: Double = 40.0,
) : Serializable

/** Parses `x1:y1-x2:y2`. */
internal data class Region(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
  override fun toString() = "$x1:$y1-$x2:$y2"

  companion object {
    private val FORMAT = Regex("""(\d+):(\d+)-(\d+):(\d+)""")

    fun parse(text: String): Region {
      val m = FORMAT.matchEntire(text.trim())
        ?: throw dev.medusa.core.MedusaException("Invalid ignore region '$text', expected x1:y1-x2:y2")
      val (x1, y1, x2, y2) = m.destructured
      return Region(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
    }
  }
}
