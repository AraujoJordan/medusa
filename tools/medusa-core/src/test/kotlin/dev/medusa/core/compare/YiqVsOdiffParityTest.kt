package dev.medusa.core.compare

import app.cash.paparazzi.Differ
import dev.medusa.core.Images
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs the real `odiff` binary and [YiqDiffer] on the same pairs of images and requires the same number of differing
 * pixels. The pairs have genuinely antialiased text edges (drawn with Java2D), so the antialiasing detection is exercised.
 * Skipped when `odiff` is not installed.
 */
class YiqVsOdiffParityTest {
  private val dir = createTempDirectory("yiq-parity").toFile()

  private val odiff: File? = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
    .map { File(it, "odiff") }.firstOrNull { it.canExecute() }

  private fun text(shiftX: Int = 0, fontSize: Int = 22, rectColor: Int = 0xFF3366CC.toInt(), alpha: Int = 255): BufferedImage {
    val img = BufferedImage(160, 90, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(0xFAFAFA)
    g.fillRect(0, 0, 160, 90)
    g.color = Color(rectColor, true)
    g.fillRoundRect(10, 50, 120, 30, 14, 14)
    g.color = Color.BLACK
    g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
    g.drawString("Medusa 123", 10 + shiftX, 36)
    g.dispose()
    if (alpha < 255) {
      for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, (alpha shl 24) or (img.getRGB(x, y) and 0xFFFFFF))
    }
    return img
  }

  private fun withDot(base: BufferedImage) = copy(base).also { it.setRGB(5, 5, 0xFF000000.toInt()) }

  private fun noisy(base: BufferedImage, amplitude: Int, seed: Int): BufferedImage {
    val r = Random(seed)
    return copy(base).also { img ->
      for (y in 0 until img.height) for (x in 0 until img.width) {
        val p = img.getRGB(x, y)
        fun ch(shift: Int) = (((p shr shift) and 0xFF) + r.nextInt(-amplitude, amplitude + 1)).coerceIn(0, 255)
        img.setRGB(x, y, (p and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
      }
    }
  }

  private fun copy(b: BufferedImage) = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB).also { c ->
    for (y in 0 until b.height) for (x in 0 until b.width) c.setRGB(x, y, b.getRGB(x, y))
  }

  private fun odiffCount(base: File, other: File, threshold: Double, aa: Boolean): Long {
    val cmd = buildList {
      add(odiff!!.path); add(base.path); add(other.path); add("--parsable-stdout"); add("-t"); add(threshold.toString())
      if (aa) add("--aa")
    }
    val out = ProcessBuilder(cmd).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
    return out.trim().substringBefore(';').toLongOrNull() ?: error("unexpected odiff output: $out")
  }

  private fun yiqCount(base: BufferedImage, other: BufferedImage, threshold: Double, aa: Boolean): Long =
    when (val r = YiqDiffer(threshold, aa).compare(base, other)) {
      is Differ.DiffResult.Identical, is Differ.DiffResult.Similar -> 0
      is Differ.DiffResult.Different -> r.numDifferentPixels
    }

  @Test
  fun `YiqDiffer counts exactly the same differing pixels as the odiff binary`() {
    assumeTrue(odiff != null, "odiff is not installed (brew install odiff)")
    val base = text()
    val pairs = mapOf(
      "identical" to text(),
      "text shifted 1px" to text(shiftX = 1),
      "text shifted 3px" to text(shiftX = 3),
      "bigger font" to text(fontSize = 24),
      "rect colour +16" to text(rectColor = 0xFF4376DC.toInt()),
      "rect colour +2" to text(rectColor = 0xFF3568CE.toInt()),
      "one dot" to withDot(base),
      "alpha 250" to text(alpha = 250),
      "alpha 128" to text(alpha = 128),
      "noise +-3" to noisy(base, 3, 1),
      "noise +-12" to noisy(base, 12, 2),
      "noise +-40" to noisy(base, 40, 3),
    )
    val baseFile = File(dir, "base.png").also { Images.write(base, it) }
    var checked = 0
    var nonZero = 0
    for ((name, other) in pairs) {
      val otherFile = File(dir, "${name.replace(' ', '_').replace('+', 'p')}.png").also { Images.write(other, it) }
      // Compare what odiff reads from disk with what we read from the same file.
      val fromDisk = Images.read(otherFile)
      val baseFromDisk = Images.read(baseFile)
      for (threshold in listOf(0.0, 0.05, 0.1, 0.3)) {
        for (aa in listOf(false, true)) {
          val expected = odiffCount(baseFile, otherFile, threshold, aa)
          val actual = yiqCount(baseFromDisk, fromDisk, threshold, aa)
          assertEquals(expected, actual, "$name, threshold=$threshold, aa=$aa: odiff says $expected, YiqDiffer says $actual")
          checked++
          if (expected > 0) nonZero++
        }
      }
    }
    assertTrue(checked == 12 * 4 * 2, "checked $checked")
    assertTrue(nonZero > 30, "the corpus must produce real differences, only $nonZero of $checked were non-zero")
  }
}
