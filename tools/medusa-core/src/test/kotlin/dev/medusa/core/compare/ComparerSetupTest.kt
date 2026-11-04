package dev.medusa.core.compare

import dev.medusa.core.Comparison
import dev.medusa.core.Images
import dev.medusa.core.MedusaException
import dev.medusa.core.setup.Arch
import dev.medusa.core.setup.Downloader
import dev.medusa.core.setup.OdiffInstaller
import dev.medusa.core.setup.Os
import dev.medusa.core.setup.Platform
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** odiff is the default differ: how it is found, installed, or replaced when that is impossible. */
class ComparerSetupTest {
  private val dir = createTempDirectory("comparer-setup").toFile()
  private val work = File(dir, "work")
  private val noSystemDirs = emptyList<String>()

  private val fakeBinary = "#!/bin/sh\necho 'odiff 4.5.0'\n".toByteArray()
  private val fakeSha = java.security.MessageDigest.getInstance("SHA-256").digest(fakeBinary).joinToString("") { "%02x".format(it) }

  private class FakeDownloads(private val bytes: ByteArray?) : Downloader {
    val calls = AtomicInteger()
    override fun download(url: String, target: File) {
      calls.incrementAndGet()
      target.parentFile.mkdirs()
      if (bytes == null) throw MedusaException("Downloading $url failed: offline")
      target.writeBytes(bytes)
    }
  }

  private fun installer(downloads: Downloader, installDir: File = File(dir, "installed")) = OdiffInstaller(
    installDir, Platform(Os.MACOS, Arch.ARM64), downloads, mapOf("odiff-macos-arm64" to fakeSha), "https://example.test",
  )

  private fun create(spec: ComparerSpec, installer: OdiffInstaller?, notices: MutableList<String>, path: String = ""): ImageComparer =
    ImageComparers.create(spec, work, 1, path, { notices += it }, installer, noSystemDirs)

  private fun spec(installDir: File = File(dir, "installed"), autoInstall: Boolean = true, fallback: Boolean = true) =
    ComparerSpec(installDir = installDir.path, autoInstall = autoInstall, fallbackToYiq = fallback)

  private fun image(argb: Int = 0xFFFFFFFF.toInt(), dot: Boolean = false) =
    File(dir, "i${System.nanoTime()}.png").also { f ->
      val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
      for (y in 0 until 20) for (x in 0 until 20) img.setRGB(x, y, argb)
      if (dot) for (x in 0 until 10) img.setRGB(x, 3, 0xFF000000.toInt())
      Images.write(img, f)
    }

  @Test
  fun `odiff is the default differ`() {
    assertEquals("odiff", ComparerSpec().differ)
    assertTrue(ComparerSpec().autoInstall && ComparerSpec().fallbackToYiq)
  }

  @Test
  fun `an odiff on PATH is used and nothing is installed`() {
    val bin = File(dir, "pathbin").also { it.mkdirs() }
    File(bin, "odiff").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
    val downloads = FakeDownloads(fakeBinary)

    val c = create(spec(), installer(downloads), mutableListOf(), path = bin.path)

    assertIs<OdiffImageComparer>(c)
    assertEquals(0, downloads.calls.get())
  }

  @Test
  fun `an odiff installed by Medusa earlier is found without downloading`() {
    val installDir = File(dir, "installed").also { it.mkdirs() }
    File(installDir, "odiff").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
    val downloads = FakeDownloads(fakeBinary)

    val c = create(spec(installDir), installer(downloads, installDir), mutableListOf())

    assertIs<OdiffImageComparer>(c)
    assertEquals(0, downloads.calls.get())
  }

  @Test
  fun `a missing odiff is installed on first use and the user is told`() {
    val downloads = FakeDownloads(fakeBinary)
    val notices = mutableListOf<String>()

    val c = create(spec(), OdiffInstaller(File(dir, "installed"), Platform(Os.MACOS, Arch.ARM64), downloads, mapOf("odiff-macos-arm64" to fakeSha), "https://example.test", { notices += it }), notices)

    assertIs<OdiffImageComparer>(c)
    assertEquals(1, downloads.calls.get())
    assertTrue(File(dir, "installed/odiff").canExecute())
    assertTrue(notices.any { "Installing odiff" in it }, notices.toString())
  }

  @Test
  fun `when odiff cannot be installed Medusa warns and compares with the built-in yiq differ`() {
    val notices = mutableListOf<String>()

    val c = create(spec(), installer(FakeDownloads(null)), notices)

    assertIs<PaparazziImageComparer>(c)
    val warning = notices.single { "WARNING" in it }
    assertTrue("offline" in warning && "yiq" in warning && "medusaSetup" in warning, warning)
    assertIs<Comparison.Passed>(c.compare(image(), image()))
    assertIs<Comparison.Failed>(c.compare(image(), image(dot = true)))
  }

  @Test
  fun `the fallback uses the odiff threshold so the verdicts stay comparable`() {
    val c = create(ComparerSpec(odiff = OdiffOptions(threshold = 0.0), autoInstall = false, installDir = File(dir, "x").path), null, mutableListOf())

    // 5 levels darker everywhere: invisible at odiff's default threshold 0.1, a difference at threshold 0
    assertIs<Comparison.Failed>(c.compare(image(), image(argb = 0xFFFAFAFA.toInt())))
  }

  @Test
  fun `with autoInstall off a missing odiff is not downloaded`() {
    val downloads = FakeDownloads(fakeBinary)
    val notices = mutableListOf<String>()

    val c = create(spec(autoInstall = false), installer(downloads), notices)

    assertIs<PaparazziImageComparer>(c)
    assertEquals(0, downloads.calls.get())
    assertTrue(notices.single().contains("autoInstall is off"), notices.toString())
  }

  @Test
  fun `with the fallback off a missing odiff is an error that says how to install it`() {
    val e = assertFailsWith<MedusaException> { create(spec(fallback = false), installer(FakeDownloads(null)), mutableListOf()) }

    assertTrue("medusaSetup" in e.message!! && "brew install odiff" in e.message!!, e.message)
  }

  @Test
  fun `an explicitly configured binary is never replaced, even if it is wrong`() {
    val downloads = FakeDownloads(fakeBinary)
    val s = spec().copy(odiff = OdiffOptions(binary = "/nonexistent/odiff"))

    val e = assertFailsWith<MedusaException> { create(s, installer(downloads), mutableListOf()) }

    assertTrue("not found at the configured path: /nonexistent/odiff" in e.message!!, e.message)
    assertEquals(0, downloads.calls.get())
  }

  @Test
  fun `default and empty differ names mean odiff`() {
    val notices = mutableListOf<String>()
    for (name in listOf("", "default", "ODIFF")) {
      val c = create(spec().copy(differ = name), installer(FakeDownloads(null)), notices)
      assertIs<PaparazziImageComparer>(c, "$name resolves like odiff, which falls back here because it cannot be installed")
    }
    assertEquals(3, notices.count { "WARNING" in it })
  }
}
