package dev.medusa.core.compare

import app.cash.paparazzi.Differ
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * A Kotlin port of odiff's pixel comparison (https://github.com/dmtrKovalenko/odiff, MIT, itself derived from
 * pixelmatch by Mapbox, ISC): two pixels differ when their YIQ colour distance exceeds `35215 * threshold^2`,
 * optionally ignoring antialiased pixels. It follows odiff's fixed-point arithmetic (12 bit) and its 3x3 antialiasing
 * detection (`src/color_delta.zig`, `src/antialiasing.zig`, `src/diff.zig`), so it gives the same count as the `odiff`
 * binary on the same pair of same-size images, without needing the binary.
 *
 * It is a Paparazzi [Differ], so it runs through [PaparazziImageComparer] and shares its pass/fail rule. The percentage
 * is the share of differing pixels. A pixel that exists in only one of two different-size images counts as different.
 */
class YiqDiffer(
  private val threshold: Double = 0.1,
  private val antialiasing: Boolean = false,
) : Differ {
  init {
    require(threshold in 0.0..1.0) { "threshold must be between 0 and 1, was $threshold" }
  }

  override fun compare(expected: BufferedImage, actual: BufferedImage): Differ.DiffResult {
    val ew = expected.width
    val eh = expected.height
    val aw = actual.width
    val ah = actual.height
    val e = IntArray(ew * eh).also { expected.getRGB(0, 0, ew, eh, it, 0, ew) }
    val a = IntArray(aw * ah).also { actual.getRGB(0, 0, aw, ah, it, 0, aw) }
    val maxWidth = max(ew, aw)
    val maxHeight = max(eh, ah)
    val maxDelta = (MAX_YIQ_POSSIBLE_DELTA * threshold * threshold * ONE).toLong()

    val delta = BufferedImage(ew + maxWidth + aw, maxHeight, BufferedImage.TYPE_INT_ARGB)
    var different = 0L
    for (y in 0 until maxHeight) {
      for (x in 0 until maxWidth) {
        val inExpected = x < ew && y < eh
        val inActual = x < aw && y < ah
        val differs = when {
          !inExpected || !inActual -> true
          else -> {
            val pe = e[y * ew + x]
            val pa = a[y * aw + x]
            pe != pa && colorDelta(pe, pa) > maxDelta &&
              !(antialiasing && (isAntialiased(x, y, e, ew, eh, a, aw, ah) || isAntialiased(x, y, a, aw, ah, e, ew, eh)))
          }
        }
        if (differs) different++
        delta.setRGB(ew + x, y, if (differs) RED else UNCHANGED)
      }
    }
    val g = delta.createGraphics()
    g.drawImage(expected, 0, 0, null)
    g.drawImage(actual, ew + maxWidth, 0, null)
    g.dispose()

    if (different == 0L) return Differ.DiffResult.Identical(delta)
    val percent = (100.0 * different / (maxWidth.toDouble() * maxHeight)).toFloat()
    return Differ.DiffResult.Different(delta, percent, different)
  }

  internal companion object {
    private const val SHIFT = 12
    private const val ONE = 1 shl SHIFT
    private const val MAX_YIQ_POSSIBLE_DELTA = 35215.0
    private const val RED = 0xFFFF0000.toInt()
    private const val UNCHANGED = 0x00808080

    private const val Y_R = 0.29889531
    private const val Y_G = 0.58662247
    private const val Y_B = 0.11448223
    private const val I_R = 0.59597799
    private const val I_G = -0.27417610
    private const val I_B = -0.32180189
    private const val Q_R = 0.21147017
    private const val Q_G = -0.52261711
    private const val Q_B = 0.31114694

    // Truncated, not rounded: odiff's source says @round, but the shipped odiff 4.5.0 behaves as if the fixed-point
    // coefficients were truncated. Fitted against the binary: truncation reproduced every verdict on a 14,400 pixel noise
    // pair (threshold 0.05), rounding got 3 of them wrong. YiqVsOdiffParityTest keeps it honest.
    private fun fixed(v: Double) = (v * ONE).toLong()
    private val YR = fixed(Y_R)
    private val YG = fixed(Y_G)
    private val YB = fixed(Y_B)
    private val IR = fixed(I_R)
    private val IG = fixed(I_G)
    private val IB = fixed(I_B)
    private val QR = fixed(Q_R)
    private val QG = fixed(Q_G)
    private val QB = fixed(Q_B)
    private val Y_WEIGHT = fixed(0.5053)
    private val I_WEIGHT = fixed(0.299)
    private val Q_WEIGHT = fixed(0.1957)

    /** Blend a channel over white, in 12 bit fixed point (odiff's `blendChannelSimd`). */
    private fun blend(channel: Long, alpha: Long): Long =
      if (alpha == 0L) 255L shl SHIFT else (255L shl SHIFT) + (channel - 255) * alpha * ONE / 255

    /** odiff's `calculatePixelColorDeltaSimd`, on ARGB ints. */
    fun colorDelta(pa: Int, pb: Int): Long {
      val ar = blend(((pa shr 16) and 0xFF).toLong(), (pa ushr 24).toLong())
      val ag = blend(((pa shr 8) and 0xFF).toLong(), (pa ushr 24).toLong())
      val ab = blend((pa and 0xFF).toLong(), (pa ushr 24).toLong())
      val br = blend(((pb shr 16) and 0xFF).toLong(), (pb ushr 24).toLong())
      val bg = blend(((pb shr 8) and 0xFF).toLong(), (pb ushr 24).toLong())
      val bb = blend((pb and 0xFF).toLong(), (pb ushr 24).toLong())
      val y = ((ar * YR + ag * YG + ab * YB) shr SHIFT) - ((br * YR + bg * YG + bb * YB) shr SHIFT)
      val i = ((ar * IR + ag * IG + ab * IB) shr SHIFT) - ((br * IR + bg * IG + bb * IB) shr SHIFT)
      val q = ((ar * QR + ag * QG + ab * QB) shr SHIFT) - ((br * QR + bg * QG + bb * QB) shr SHIFT)
      return (y * y * Y_WEIGHT + i * i * I_WEIGHT + q * q * Q_WEIGHT) shr (2 * SHIFT)
    }

    /** Brightness (Y) of a pixel blended over white, in floating point (odiff's `calculatePixelBrightnessDelta` operand). */
    private fun brightness(p: Int): Double {
      val alpha = p ushr 24
      var r = ((p shr 16) and 0xFF).toDouble()
      var g = ((p shr 8) and 0xFF).toDouble()
      var b = (p and 0xFF).toDouble()
      when {
        alpha == 0 -> { r = 255.0; g = 255.0; b = 255.0 }
        alpha < 255 -> {
          val na = alpha / 255.0
          r = 255.0 + (r - 255.0) * na
          g = 255.0 + (g - 255.0) * na
          b = 255.0 + (b - 255.0) * na
        }
      }
      return r * Y_R + g * Y_G + b * Y_B
    }

    private fun hasManySiblingsWithSameColor(x: Int, y: Int, w: Int, h: Int, img: IntArray): Boolean {
      if (x > w - 1 || y > h - 1) return false
      val x0 = if (x > 0) x - 1 else 0
      val y0 = if (y > 0) y - 1 else 0
      val x1 = minOf(x + 1, w - 1)
      val y1 = minOf(y + 1, h - 1)
      var zeroes = if (x == x0 || x == x1 || y == y0 || y == y1) 1 else 0
      val base = img[y * w + x]
      for (ay in y0..y1) {
        for (ax in x0..x1) {
          if (zeroes < 3 && (x != ax || y != ay) && base == img[ay * w + ax]) zeroes++
        }
      }
      return zeroes >= 3
    }

    /** odiff's `antialiasing.detect`: is the pixel at (x, y) of [base] an antialiased edge pixel, judged against [comp]. */
    fun isAntialiased(x: Int, y: Int, base: IntArray, bw: Int, bh: Int, comp: IntArray, cw: Int, ch: Int): Boolean {
      val x0 = if (x > 0) x - 1 else 0
      val y0 = if (y > 0) y - 1 else 0
      val x1 = minOf(x + 1, bw - 1)
      val y1 = minOf(y + 1, bh - 1)
      var minDelta = 0.0
      var maxDelta = 0.0
      var minX = 0
      var minY = 0
      var maxX = 0
      var maxY = 0
      var zeroes = if (x == x0 || x == x1 || y == y0 || y == y1) 1 else 0
      val color = base[y * bw + x]
      for (ay in y0..y1) {
        for (ax in x0..x1) {
          if (zeroes < 3 && (x != ax || y != ay)) {
            val adjacent = base[ay * bw + ax]
            if (color == adjacent) {
              zeroes++
            } else {
              val d = brightness(color) - brightness(adjacent)
              if (d < minDelta) {
                minDelta = d; minX = ax; minY = ay
              } else if (d > maxDelta) {
                maxDelta = d; maxX = ax; maxY = ay
              }
            }
          }
        }
      }
      if (zeroes >= 3 || minDelta == 0.0 || maxDelta == 0.0) return false
      val inBase = hasManySiblingsWithSameColor(minX, minY, bw, bh, base) || hasManySiblingsWithSameColor(maxX, maxY, bw, bh, base)
      val inComp = hasManySiblingsWithSameColor(minX, minY, cw, ch, comp) || hasManySiblingsWithSameColor(maxX, maxY, cw, ch, comp)
      return inBase && inComp
    }
  }
}
