package dev.medusa.core

import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the **released Paparazzi jar** (loaded in its own class loader, so it does not clash with the vendored copy)
 * and Medusa on the same image pairs, and requires the same verdict and the same percentage.
 *
 * Upstream side: `ImageUtils.assertImageSimilar` (throws AssertionError when the images are not similar) with
 * upstream's own `OffByTwo` / `PixelPerfect`. Medusa side: [PaparazziComparison] with the vendored differs.
 * The upstream class path comes from the `upstream` Gradle configuration (system property `upstream.classpath`).
 */
class UpstreamParityTest {
  private val upstream = URLClassLoader(
    System.getProperty("upstream.classpath").split(File.pathSeparator).map { File(it).toURI().toURL() }.toTypedArray(),
    ClassLoader.getPlatformClassLoader(), // BufferedImage & co. are shared with the platform, nothing else is
  )
  private val failureDir = createTempDirectory("upstream-failures").toFile()

  private val imageUtils = upstream.loadClass("app.cash.paparazzi.internal.ImageUtils").getField("INSTANCE").get(null)
  private val differInterface = upstream.loadClass("app.cash.paparazzi.Differ")
  private val assertSimilar = imageUtils.javaClass.getMethod(
    "assertImageSimilar", String::class.java, BufferedImage::class.java, BufferedImage::class.java,
    Double::class.javaPrimitiveType, File::class.java, differInterface,
  )

  private fun upstreamDiffer(name: String): Any =
    upstream.loadClass("app.cash.paparazzi.internal.differs.$name").getField("INSTANCE").get(null)

  /** null when upstream considers the images similar, else its error message. */
  private fun upstreamVerdict(golden: BufferedImage, actual: BufferedImage, max: Double, differ: String): String? = try {
    assertSimilar.invoke(imageUtils, File(failureDir, "golden.png").path, golden, actual, max, failureDir, upstreamDiffer(differ))
    null
  } catch (e: InvocationTargetException) {
    (e.targetException as? AssertionError)?.message ?: throw e
  }

  private fun solid(w: Int, h: Int, argb: Int, type: Int = BufferedImage.TYPE_INT_ARGB) =
    BufferedImage(w, h, type).also { img -> for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb) }

  private fun noise(w: Int, h: Int, seed: Int, alpha: Boolean = false) =
    BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { img ->
      val r = Random(seed)
      for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (if (alpha) r.nextInt() else 0xFF000000.toInt() or r.nextInt(0xFFFFFF)))
    }

  private fun withPixels(base: BufferedImage, vararg changes: Pair<Pair<Int, Int>, Int>) =
    BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB).also { copy ->
      for (y in 0 until base.height) for (x in 0 until base.width) copy.setRGB(x, y, base.getRGB(x, y))
      changes.forEach { (xy, color) -> copy.setRGB(xy.first, xy.second, color) }
    }

  private val white = 0xFFFFFFFF.toInt()

  private fun pairs(): List<Triple<String, BufferedImage, BufferedImage>> = buildList {
    val base = solid(100, 100, white)
    add(Triple("identical", base, solid(100, 100, white)))
    for (d in 1..4) add(Triple("blue -$d everywhere", base, solid(100, 100, white - d)))
    add(Triple("one pixel black (percent below 0.01)", base, withPixels(base, (3 to 4) to 0xFF000000.toInt())))
    add(Triple("five pixels black (percent above 0.01)", base, withPixels(base, *Array(5) { (it to 0) to 0xFF000000.toInt() })))
    add(Triple("alpha only", solid(20, 20, white), solid(20, 20, 0x80FFFFFF.toInt())))
    add(Triple("both fully transparent, colors differ", solid(8, 8, 0x00112233), solid(8, 8, 0x00FFEEDD)))
    for ((dw, dh) in listOf(1 to 0, 0 to 1, 2 to 0, 0 to 2, 3 to 3, -1 to 0, 0 to -2)) {
      add(Triple("size ${100 + dw}x${100 + dh}", base, solid(100 + dw, 100 + dh, white)))
    }
    add(Triple("rgb golden", solid(40, 40, white, BufferedImage.TYPE_INT_RGB), solid(40, 40, white)))
    add(Triple("3byte golden", solid(40, 40, white, BufferedImage.TYPE_3BYTE_BGR), solid(40, 40, white)))
    for (seed in 1..12) {
      val w = 20 + seed * 7
      val h = 15 + seed * 5
      add(Triple("noise $seed vs same", noise(w, h, seed), noise(w, h, seed)))
      add(Triple("noise $seed vs other", noise(w, h, seed), noise(w, h, seed + 100)))
      add(Triple("alpha noise $seed", noise(w, h, seed, alpha = true), noise(w, h, seed + 1, alpha = true)))
    }
  }

  @Test
  fun `identical featureless images get the same verdict from every differ as in the released Paparazzi`() {
    val verdicts = mutableMapOf<String, Boolean>()
    for ((upstreamName, medusaName) in listOf(
      "OffByTwo" to "offbytwo", "PixelPerfect" to "pixelperfect", "Mssim" to "mssim",
      "Sift" to "sift", "Flip" to "flip", "DeltaE2000" to "de2000",
    )) {
      val expected = upstreamVerdict(solid(48, 48, white), solid(48, 48, white), 0.01, upstreamName)
      val ours = PaparazziComparison.compare(solid(48, 48, white), solid(48, 48, white), Differs.byName(medusaName), 0.01)
      assertEquals(expected == null, ours is Comparison.Passed, "$medusaName on identical solid images: upstream '$expected', Medusa ${ours.javaClass.simpleName}")
      verdicts[medusaName] = expected == null
    }
    // The point of this test is the quirk it documents: at least one differ cannot handle flat images, upstream included.
    assertTrue(verdicts.values.any { !it }, "expected Sift to reject identical solid images, as upstream does: $verdicts")
    assertEquals(false, verdicts["sift"])
  }

  @Test
  fun `verdict and percentage match the released Paparazzi for every pair, differ and tolerance`() {
    var compared = 0
    var failuresSeen = 0
    for ((differName, medusaDiffer) in listOf("OffByTwo" to Differs.byName("offbytwo"), "PixelPerfect" to Differs.byName("pixelperfect"))) {
      for (max in listOf(0.0, 0.01, 1.0, 100.0)) {
        for ((label, golden, actual) in pairs()) {
          val expected = upstreamVerdict(golden, actual, max, differName)
          val ours = PaparazziComparison.compare(golden, actual, medusaDiffer, max)
          val context = "$label / $differName / max=$max"
          when (ours) {
            is Comparison.Passed -> assertEquals(null, expected, "upstream rejected what Medusa accepted: $context")
            is Comparison.Failed -> {
              failuresSeen++
              assertTrue(expected != null, "Medusa rejected what upstream accepted ($context): ${ours.reason}")
              // Same rule and numbers. Upstream names the image ("Widths differ too much for golden.png: ..."), Medusa does not.
              val upstreamReason = expected.replace(Regex(""" for \S+:"""), ":")
              assertTrue(upstreamReason.startsWith(ours.reason), "$context\n upstream: $expected\n medusa:   ${ours.reason}")
            }
          }
          compared++
        }
      }
    }
    // Make sure the comparison was not vacuous: plenty of pairs, and both outcomes occurred.
    assertTrue(compared > 400, "compared only $compared")
    assertTrue(failuresSeen > 50 && failuresSeen < compared - 50, "failures seen: $failuresSeen of $compared")
  }
}
