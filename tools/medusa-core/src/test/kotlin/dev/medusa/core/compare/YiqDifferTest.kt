package dev.medusa.core.compare

import app.cash.paparazzi.Differ
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class YiqDifferTest {
  private fun solid(w: Int, h: Int, argb: Int) =
    BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { img -> for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb) }

  private fun count(r: Differ.DiffResult) = if (r is Differ.DiffResult.Different) r.numDifferentPixels else 0L

  private val white = 0xFFFFFFFF.toInt()

  @Test
  fun `identical images`() {
    assertIs<Differ.DiffResult.Identical>(YiqDiffer().compare(solid(10, 10, white), solid(10, 10, white)))
  }

  @Test
  fun `a small colour change is below the default threshold but any change is caught at threshold zero`() {
    val a = solid(10, 10, white)
    val b = solid(10, 10, 0xFFFAFAFA.toInt()) // 5 levels darker everywhere

    assertIs<Differ.DiffResult.Identical>(YiqDiffer(threshold = 0.1).compare(a, b))
    assertEquals(100L, count(YiqDiffer(threshold = 0.0).compare(a, b)))
  }

  @Test
  fun `a large change is caught at the default threshold and reports the share of pixels`() {
    val a = solid(10, 10, white)
    val b = solid(10, 10, white).also { it.setRGB(2, 2, 0xFF000000.toInt()); it.setRGB(3, 2, 0xFF000000.toInt()) }

    val result = assertIs<Differ.DiffResult.Different>(YiqDiffer().compare(a, b))

    assertEquals(2L, result.numDifferentPixels)
    assertEquals(2f, result.percentDifference)
  }

  @Test
  fun `colour distance is symmetric and zero for equal colours`() {
    val x = 0xFF336699.toInt()
    val y = 0xFFCC9933.toInt()
    assertEquals(0L, YiqDiffer.colorDelta(x, x))
    assertEquals(YiqDiffer.colorDelta(x, y), YiqDiffer.colorDelta(y, x))
    assertTrue(YiqDiffer.colorDelta(white, 0xFF000000.toInt()) > YiqDiffer.colorDelta(white, 0xFF808080.toInt()))
  }

  @Test
  fun `transparent pixels are compared as if blended over white`() {
    // fully transparent black and fully transparent white both look white
    assertEquals(0L, YiqDiffer.colorDelta(0x00000000, 0x00FFFFFF))
    // half transparent black is a mid grey over white
    assertTrue(YiqDiffer.colorDelta(0x80000000.toInt(), white) > 0)
  }

  @Test
  fun `antialiasing detection drops edge pixels that a one pixel shift creates`() {
    // a vertical black bar with a grey antialiased edge column, shifted by one pixel
    fun bar(edgeX: Int) = BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB).also { img ->
      for (y in 0 until 30) for (x in 0 until 30) {
        img.setRGB(x, y, when {
          x < edgeX -> 0xFF000000.toInt()
          x == edgeX -> 0xFF808080.toInt()
          else -> white
        })
      }
    }
    val a = bar(10)
    val b = bar(11)

    val without = count(YiqDiffer(threshold = 0.1, antialiasing = false).compare(a, b))
    val with = count(YiqDiffer(threshold = 0.1, antialiasing = true).compare(a, b))

    assertTrue(without > 0)
    assertTrue(with < without, "antialiasing detection must discount edge pixels: $with of $without")
  }

  @Test
  fun `a pixel that exists in only one of two different size images counts as different`() {
    val result = assertIs<Differ.DiffResult.Different>(YiqDiffer().compare(solid(10, 10, white), solid(11, 10, white)))

    assertEquals(10L, result.numDifferentPixels, "the extra column of 10 pixels")
    val delta = (result as Differ.DiffResult.Different).delta
    assertEquals(10 + 11 + 11, delta.width, "expected | delta | actual layout, like Paparazzi's")
  }

  @Test
  fun `invalid threshold is rejected`() {
    kotlin.test.assertFailsWith<IllegalArgumentException> { YiqDiffer(threshold = 1.5) }
    kotlin.test.assertFailsWith<IllegalArgumentException> { YiqDiffer(threshold = -0.1) }
  }
}
