package dev.medusa.core

import app.cash.paparazzi.Differ
import app.cash.paparazzi.internal.differs.OffByTwo
import app.cash.paparazzi.internal.differs.PixelPerfect
import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaparazziComparisonTest {
  private fun solid(w: Int, h: Int, argb: Int, type: Int = BufferedImage.TYPE_INT_ARGB) =
    BufferedImage(w, h, type).also { img -> for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb) }

  private fun noise(w: Int, h: Int, seed: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { img ->
    val r = Random(seed)
    for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, 0xFF000000.toInt() or r.nextInt(0xFFFFFF))
  }

  private val white = 0xFFFFFFFF.toInt()

  @Test
  fun `identical images pass`() {
    val result = assertIs<Comparison.Passed>(PaparazziComparison.compare(solid(10, 10, white), solid(10, 10, white)))
    assertEquals(0f, result.percentDifference)
  }

  @Test
  fun `off by two is similar with the default differ`() {
    val actual = solid(10, 10, 0xFFFFFFFD.toInt()) // blue 255 -> 253

    assertIs<Comparison.Passed>(PaparazziComparison.compare(solid(10, 10, white), actual))
  }

  @Test
  fun `off by three is different and reports the differ's own percentage`() {
    val expected = solid(10, 10, white)
    val actual = solid(10, 10, 0xFFFFFFFC.toInt()) // blue 255 -> 252

    val failed = assertIs<Comparison.Failed>(PaparazziComparison.compare(expected, actual, OffByTwo))
    val direct = assertIs<Differ.DiffResult.Different>(OffByTwo.compare(expected, actual))
    assertEquals(direct.percentDifference, failed.percentDifference, "Medusa must report exactly what the vendored differ computed")
    assertTrue(failed.reason.startsWith("Images differ (by "))
  }

  @Test
  fun `percentage rule is strictly greater than the maximum`() {
    val expected = solid(10, 10, white)
    val actual = solid(10, 10, 0xFFFFFFFE.toInt())
    val percent = (PixelPerfect.compare(expected, actual) as Differ.DiffResult.Different).percentDifference.toDouble()

    assertIs<Comparison.Passed>(PaparazziComparison.compare(expected, actual, PixelPerfect, maxPercentDifference = percent))
    assertIs<Comparison.Failed>(PaparazziComparison.compare(expected, actual, PixelPerfect, maxPercentDifference = percent - 0.0001))
  }

  @Test
  fun `default tolerance is Paparazzi's 0_01`() {
    assertEquals(0.01, DEFAULT_MAX_PERCENT_DIFFERENCE)
  }

  @Test
  fun `size difference of two or more fails even when the percentage would pass`() {
    val golden = solid(10, 10, white)

    val width = assertIs<Comparison.Failed>(PaparazziComparison.compare(golden, solid(12, 10, white), maxPercentDifference = 100.0))
    assertTrue(width.reason.startsWith("Widths differ too much"), width.reason)
    val height = assertIs<Comparison.Failed>(PaparazziComparison.compare(golden, solid(10, 12, white), maxPercentDifference = 100.0))
    assertTrue(height.reason.startsWith("Heights differ too much"), height.reason)
  }

  @Test
  fun `size difference of one is allowed when the percentage is within tolerance`() {
    val golden = solid(10, 10, white)

    assertIs<Comparison.Passed>(PaparazziComparison.compare(golden, solid(11, 10, white), maxPercentDifference = 100.0))
  }

  @Test
  fun `delta image has Paparazzi's layout expected, delta, actual`() {
    val failed = assertIs<Comparison.Failed>(PaparazziComparison.compare(solid(10, 8, white), solid(10, 8, 0xFF000000.toInt())))

    assertEquals(10 + 10 + 10, failed.delta.width)
    assertEquals(8, failed.delta.height)
    assertEquals(white, failed.delta.getRGB(0, 0), "expected image is drawn on the left")
    assertEquals(0xFF000000.toInt(), failed.delta.getRGB(20, 0), "actual image is drawn on the right")
  }

  @Test
  fun `a golden that is not ARGB is normalised like upstream does`() {
    val rgbGolden = solid(10, 10, white, BufferedImage.TYPE_INT_RGB)

    assertIs<Comparison.Passed>(PaparazziComparison.compare(rgbGolden, solid(10, 10, white)))
  }

  @Test
  fun `every vendored differ is selectable and agrees on identical images`() {
    val image = noise(48, 48, seed = 1)
    for (name in Differs.names) {
      val differ = Differs.byName(name)
      val result = PaparazziComparison.compare(image, noise(48, 48, seed = 1), differ)
      assertIs<Comparison.Passed>(result, "differ '$name' must pass identical images")
    }
  }

  @Test
  fun `every vendored differ rejects clearly different images`() {
    for (name in Differs.names) {
      val result = PaparazziComparison.compare(noise(48, 48, seed = 1), noise(48, 48, seed = 2), Differs.byName(name))
      assertIs<Comparison.Failed>(result, "differ '$name' must reject unrelated images")
    }
  }

  @Test
  fun `differ names match Paparazzi's, default is off by two`() {
    assertSame(OffByTwo, Differs.byName(null))
    assertSame(OffByTwo, Differs.byName("default"))
    assertSame(OffByTwo, Differs.byName("OffByTwo"))
    assertSame(PixelPerfect, Differs.byName("pixelperfect"))
    val e = assertFailsWith<MedusaException> { Differs.byName("nope") }
    assertTrue("offbytwo" in e.message!!)
  }
}
