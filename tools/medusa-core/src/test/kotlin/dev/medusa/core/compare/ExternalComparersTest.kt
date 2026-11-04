package dev.medusa.core.compare

import dev.medusa.core.Comparison
import dev.medusa.core.Images
import dev.medusa.core.MedusaException
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class ExternalComparersTest {
  private val dir = createTempDirectory("external-comparers").toFile()
  private val white = 0xFFFFFFFF.toInt()
  private val black = 0xFF000000.toInt()

  private val odiffBinary: File? = runCatching { OdiffImageComparer.locate() }.getOrNull()
  private val magickBinary: File? = runCatching { MagickImageComparer.locate() }.getOrNull()

  private var n = 0

  private fun image(w: Int = 160, h: Int = 90, argb: Int = white, edit: (BufferedImage) -> Unit = {}): File {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb)
    edit(img)
    return File(dir, "img${n++}.png").also { Images.write(img, it) }
  }

  private fun dots(count: Int): (BufferedImage) -> Unit = { img -> repeat(count) { img.setRGB((it % 50) * 3 + 2, 5 + (it / 50) * 3, black) } }

  /** A script that would fail the test if it were ever run. */
  private fun forbiddenBinary() = script("forbidden", "echo 'must not run' >&2; exit 99")

  private fun script(name: String, body: String): File =
    File(dir, name).also { it.writeText("#!/bin/bash\n$body\n"); it.setExecutable(true) }

  private fun odiff(
    threshold: Double = 0.1,
    aa: Boolean = false,
    regions: List<String> = emptyList(),
    max: Double = 0.01,
    server: Boolean = true,
    parallelism: Int = 2,
    binary: File = odiffBinary!!,
  ) = OdiffImageComparer(binary, threshold, aa, regions, max, File(dir, "work"), parallelism, server)

  private fun magick(metric: String = "AE", fuzz: String? = "2%", max: Double = 0.01, minPsnr: Double = 40.0, binary: File = magickBinary!!) =
    MagickImageComparer(binary, metric, fuzz, minPsnr, max, File(dir, "work"))

  private fun requireOdiff() = assumeTrue(odiffBinary != null, "odiff is not installed (brew install odiff)")
  private fun requireMagick() = assumeTrue(magickBinary != null, "ImageMagick is not installed (brew install imagemagick)")

  // ------------------------------------------------------------------ odiff

  @Test
  fun `odiff passes identical images and fails a real change with a three part delta image`() {
    requireOdiff()
    odiff().use { c ->
      assertIs<Comparison.Passed>(c.compare(image(), image()))

      val failed = assertIs<Comparison.Failed>(c.compare(image(), image(edit = dots(40))))
      assertTrue("odiff: 40 of 14400 pixels differ" in failed.reason, failed.reason)
      assertEquals(160 + 160 + 160, failed.delta.width, "expected | diff | actual")
      assertEquals(90, failed.delta.height)
    }
  }

  @Test
  fun `odiff tolerance is a share of pixels`() {
    requireOdiff()
    odiff(max = 0.01).use { c ->
      assertIs<Comparison.Passed>(c.compare(image(), image(edit = dots(1))), "1 of 14400 pixels is 0.007%")
      assertIs<Comparison.Failed>(c.compare(image(), image(edit = dots(5))), "5 of 14400 pixels is 0.035%")
    }
  }

  @Test
  fun `odiff threshold decides how different a colour must be`() {
    requireOdiff()
    val base = image()
    val faint = image(argb = 0xFFFAFAFA.toInt()) // 5 levels darker everywhere

    odiff(threshold = 0.1).use { assertIs<Comparison.Passed>(it.compare(base, faint)) }
    odiff(threshold = 0.0).use { assertIs<Comparison.Failed>(it.compare(base, faint)) }
  }

  @Test
  fun `odiff ignore regions hide a changed block`() {
    requireOdiff()
    val base = image()
    val changed = image(edit = { img -> for (y in 20 until 30) for (x in 20 until 30) img.setRGB(x, y, black) })

    odiff().use { assertIs<Comparison.Failed>(it.compare(base, changed)) }
    odiff(regions = listOf("15:15-35:35")).use { assertIs<Comparison.Passed>(it.compare(base, changed)) }
  }

  @Test
  fun `odiff server and one process per comparison give the same percentage`() {
    requireOdiff()
    val base = image()
    for (changed in listOf(image(edit = dots(8)), image(edit = dots(30)), image(argb = 0xFF336699.toInt()))) {
      val viaServer = odiff(server = true).use { assertIs<Comparison.Failed>(it.compare(base, changed)) }
      val oneShot = odiff(server = false).use { assertIs<Comparison.Failed>(it.compare(base, changed)) }
      assertEquals(viaServer.percentDifference, oneShot.percentDifference)
    }
  }

  @Test
  fun `a size change fails before the external tool is started`() {
    val result = odiff(binary = forbiddenBinary()).use { it.compare(image(100, 100), image(101, 100)) }

    val failed = assertIs<Comparison.Failed>(result)
    assertEquals("size changed from 100x100 to 101x100", failed.reason)
    assertEquals(100 + 101 + 101, failed.delta.width)
  }

  @Test
  fun `many threads share a small pool of odiff servers and every verdict is right`() {
    requireOdiff()
    val base = image()
    val same = image()
    val different = image(edit = dots(40))
    val pool = Executors.newFixedThreadPool(8)
    odiff(parallelism = 3).use { c ->
      val results = (0 until 120).map { i -> pool.submit<Boolean> { c.compare(base, if (i % 2 == 0) same else different) is Comparison.Passed } }
      results.forEachIndexed { i, f -> assertEquals(i % 2 == 0, f.get(60, TimeUnit.SECONDS), "comparison $i") }
    }
    pool.shutdownNow()
  }

  @Test
  fun `a missing image is an error that names the file`() {
    requireOdiff()
    val e = assertFailsWith<MedusaException> { odiff().use { it.compare(File(dir, "nope.png"), image()) } }

    assertTrue("nope.png" in e.message!!, e.message)
  }

  @Test
  fun `a server that dies after one answer is replaced`() {
    val flaky = script(
      "flaky-odiff",
      """
      echo '{"ready":true}'
      read -r line
      id=${'$'}(printf '%s' "${'$'}line" | sed -E 's/.*"requestId":([0-9]+).*/\1/')
      printf '{"requestId":%s,"match":true}\n' "${'$'}id"
      """.trimIndent(),
    )
    val a = image()
    val b = image()

    odiff(binary = flaky, parallelism = 1).use { c -> repeat(4) { assertIs<Comparison.Passed>(c.compare(a, b), "comparison $it") } }
  }

  @Test
  fun `an error answer from the server is an error with its text and does not poison the pool`() {
    val broken = script(
      "broken-odiff",
      """
      echo '{"ready":true}'
      while read -r line; do
        id=${'$'}(printf '%s' "${'$'}line" | sed -E 's/.*"requestId":([0-9]+).*/\1/')
        printf '{"requestId":%s,"error":"boom"}\n' "${'$'}id"
      done
      """.trimIndent(),
    )
    odiff(binary = broken).use { c ->
      repeat(2) {
        val e = assertFailsWith<MedusaException> { c.compare(image(), image()) }
        assertTrue("boom" in e.message!!, e.message)
      }
    }
  }

  @Test
  fun `one process mode reports the output when odiff fails`() {
    val failing = script("failing-odiff", "echo 'Error: Unsupported image format'; exit 1")

    val e = assertFailsWith<MedusaException> { odiff(binary = failing, server = false).use { it.compare(image(), image()) } }

    assertTrue("Unsupported image format" in e.message!!, e.message)
  }

  // ------------------------------------------------------------------ ImageMagick

  @Test
  fun `magick passes identical images and fails a real change`() {
    requireMagick()
    magick().let { c ->
      assertIs<Comparison.Passed>(c.compare(image(), image()))
      val failed = assertIs<Comparison.Failed>(c.compare(image(), image(edit = dots(60))))
      assertTrue(failed.reason.startsWith("magick AE:"), failed.reason)
      assertEquals(160 + 160 + 160, failed.delta.width)
    }
  }

  @Test
  fun `magick fuzz hides small colour drift and no fuzz does not`() {
    requireMagick()
    val base = image()
    val drift = image(argb = 0xFFFDFDFD.toInt()) // 2 levels

    assertIs<Comparison.Passed>(magick(fuzz = "2%").compare(base, drift))
    assertIs<Comparison.Failed>(magick(fuzz = null).compare(base, drift))
  }

  @Test
  fun `magick size change is caught here because magick alone would pass it`() {
    requireMagick()
    val failed = assertIs<Comparison.Failed>(magick().compare(image(100, 100), image(101, 100)))

    assertEquals("size changed from 100x100 to 101x100", failed.reason)
  }

  @Test
  fun `magick error metrics compare a percentage and PSNR compares decibels`() {
    requireMagick()
    val base = image()
    val small = image(edit = dots(2))
    val large = image(argb = 0xFF336699.toInt())

    for (metric in listOf("RMSE", "MAE", "DSSIM")) {
      assertIs<Comparison.Passed>(magick(metric, fuzz = null, max = 50.0).compare(base, small), "$metric small, generous")
      assertIs<Comparison.Failed>(magick(metric, fuzz = null, max = 0.0001).compare(base, large), "$metric large, strict")
    }
    assertIs<Comparison.Passed>(magick("PSNR", minPsnr = 40.0).compare(base, image()), "identical is infinite dB")
    val low = assertIs<Comparison.Failed>(magick("PSNR", minPsnr = 40.0).compare(base, large))
    assertTrue(" dB is below the minimum of 40.0 dB" in low.reason, low.reason)
  }

  @Test
  fun `magick prints are parsed from real outputs`() {
    fun parse(s: String) = MagickImageComparer.parseLine(s)

    assertEquals(0.0 to 0.0, parse("0 (0)")!!.let { it.value to it.normalized })
    assertEquals(78.4314 to 0.00784314, parse("78.4314 (0.00784314)")!!.let { it.value to it.normalized })
    assertEquals(1.01573 to 1.5499e-05, parse("1.01573 (1.5499e-05)")!!.let { it.value to it.normalized })
    assertEquals(120.0 to 1.0, parse("120 (1)")!!.let { it.value to it.normalized })
    assertEquals(32767.5, parse("32767.5 (0.5)compare: search metric is unreliable for constant-color images (NCC) @ warning")!!.value)
    assertEquals(Double.POSITIVE_INFINITY, parse("inf")!!.value)
    assertNull(parse("compare: unrecognized option `-fill' @ error/compare.c/CompareImagesCommand/694."))
    assertNull(parse("warning: something"))
    assertNull(parse(""))
  }

  @Test
  fun `unsupported metric and unparsable output are errors`() {
    assertFailsWith<MedusaException> { magick("NCC", binary = forbiddenBinary()) }

    val junk = script("junk-magick", "echo 'compare: unable to open image'; exit 2")
    val e = assertFailsWith<MedusaException> { magick(binary = junk).compare(image(), image()) }
    assertTrue("unable to open image" in e.message!!, e.message)
  }

  @Test
  fun `a binary named compare is an ImageMagick 6 style call without the compare subcommand`() {
    val log = File(dir, "args.log")
    val im6 = File(dir, "bin6").also { it.mkdirs() }.let { d ->
      File(d, "compare").also {
        it.writeText("#!/bin/bash\necho \"\$*\" > '${log.path}'\necho '0 (0)' >&2\n")
        it.setExecutable(true)
      }
    }

    magick(binary = im6).compare(image(), image())

    assertTrue(log.readText().startsWith("-metric AE -fuzz 2%"), log.readText())
  }

  // ------------------------------------------------------------------ factory

  @Test
  fun `factory knows every differ and builds working comparers`() {
    assertEquals(listOf("offbytwo", "pixelperfect", "mssim", "sift", "flip", "de2000", "yiq", "odiff", "magick"), ImageComparers.names)
    val a = image()
    val b = image(edit = dots(40))
    // Not "sift": Paparazzi's own Sift rejects identical flat images (UpstreamParityTest pins that), and these are flat.
    for (name in listOf("offbytwo", "pixelperfect", "mssim", "flip", "de2000", "yiq")) {
      ImageComparers.create(ComparerSpec(differ = name), File(dir, "w")).use { c ->
        assertIs<Comparison.Passed>(c.compare(a, image()), "$name: identical")
      }
    }
    ImageComparers.create(ComparerSpec(differ = "yiq"), File(dir, "w")).use { assertIs<Comparison.Failed>(it.compare(a, b)) }
  }

  @Test
  fun `factory errors say what to do`() {
    val unknown = assertFailsWith<MedusaException> { ImageComparers.create(ComparerSpec(differ = "bogus"), dir) }
    assertTrue("odiff" in unknown.message!! && "magick" in unknown.message!! && "yiq" in unknown.message!!, unknown.message)

    val missing = assertFailsWith<MedusaException> {
      ImageComparers.create(ComparerSpec(differ = "odiff", odiff = OdiffOptions(binary = "/nonexistent/odiff")), dir)
    }
    assertTrue("not found at the configured path" in missing.message!!, missing.message)
  }

  @Test
  fun `binary locator finds a bare name on PATH and explains how to install a missing tool`() {
    val bin = File(dir, "pathbin").also { it.mkdirs() }
    val tool = File(bin, "faketool").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }

    assertEquals(tool, BinaryLocator.locate("faketool", null, bin.path, "hint"))
    assertEquals(tool, BinaryLocator.locate("x", "faketool", bin.path, "hint"), "a bare configured name is looked up on PATH")
    val e = assertFailsWith<MedusaException> { BinaryLocator.locate("definitely-not-installed-zzz", null, "", "brew install zzz") }
    assertTrue("brew install zzz" in e.message!!, e.message)
    assertNotNull(e)
  }
}
