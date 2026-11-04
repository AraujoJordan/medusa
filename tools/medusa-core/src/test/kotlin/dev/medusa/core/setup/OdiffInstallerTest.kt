package dev.medusa.core.setup

import dev.medusa.core.MedusaException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OdiffInstallerTest {
  private val dir = createTempDirectory("odiff-install").toFile()
  private val macArm = Platform(Os.MACOS, Arch.ARM64)

  /** A tiny shell script that answers `--version`, standing in for the real binary. */
  private val fakeBinary = "#!/bin/sh\necho 'odiff 4.5.0'\n".toByteArray()
  private val fakeSha = java.security.MessageDigest.getInstance("SHA-256").digest(fakeBinary).joinToString("") { "%02x".format(it) }

  private class CountingDownloader(private val content: ByteArray, private val fail: Boolean = false) : Downloader {
    val calls = AtomicInteger()
    val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
    override fun download(url: String, target: File) {
      calls.incrementAndGet()
      urls += url
      if (fail) throw MedusaException("Downloading $url failed: HTTP 404")
      Thread.sleep(50) // long enough for concurrent callers to pile up
      target.writeBytes(content)
    }
  }

  private fun installer(
    downloader: Downloader,
    platform: Platform = macArm,
    catalog: Map<String, String> = mapOf("odiff-macos-arm64" to fakeSha),
    logs: MutableList<String>? = null,
  ) = OdiffInstaller(File(dir, "bin"), platform, downloader, catalog, "https://example.test/v1", { logs?.add(it) })

  @Test
  fun `the release asset follows the platform`() {
    val all = OdiffInstaller.PINNED_SHA256
    fun asset(os: Os, arch: Arch) = OdiffInstaller(dir, Platform(os, arch), Downloader { _, _ -> }, all).assetName()

    assertEquals("odiff-macos-arm64", asset(Os.MACOS, Arch.ARM64))
    assertEquals("odiff-macos-x64", asset(Os.MACOS, Arch.X64))
    assertEquals("odiff-linux-x64", asset(Os.LINUX, Arch.X64))
    assertEquals("odiff-linux-arm64", asset(Os.LINUX, Arch.ARM64))
    assertEquals("odiff-windows-x64.exe", asset(Os.WINDOWS, Arch.X64))
    assertEquals("odiff-windows-arm64.exe", asset(Os.WINDOWS, Arch.ARM64))
    assertNull(asset(Os.LINUX, Arch.OTHER), "no riscv64 build is pinned")
    assertNull(asset(Os.OTHER, Arch.X64))
  }

  @Test
  fun `every pinned asset has a sha256 and the names match what odiff publishes`() {
    assertEquals(6, OdiffInstaller.PINNED_SHA256.size)
    OdiffInstaller.PINNED_SHA256.values.forEach { assertTrue(Regex("[0-9a-f]{64}").matches(it), it) }
    assertEquals(
      setOf("odiff-macos-arm64", "odiff-macos-x64", "odiff-linux-x64", "odiff-linux-arm64", "odiff-windows-x64.exe", "odiff-windows-arm64.exe"),
      OdiffInstaller.PINNED_SHA256.keys,
    )
  }

  @Test
  fun `platform is read from the JVM's os properties`() {
    assertEquals(Platform(Os.MACOS, Arch.ARM64), Platform.current("Mac OS X", "aarch64"))
    assertEquals(Platform(Os.MACOS, Arch.X64), Platform.current("Mac OS X", "x86_64"))
    assertEquals(Platform(Os.LINUX, Arch.X64), Platform.current("Linux", "amd64"))
    assertEquals(Platform(Os.WINDOWS, Arch.X64), Platform.current("Windows 11", "amd64"))
    assertEquals(Platform(Os.OTHER, Arch.OTHER), Platform.current("FreeBSD", "riscv64"))
    assertEquals("odiff.exe", Platform(Os.WINDOWS, Arch.X64).executableName("odiff"))
    assertEquals("odiff", macArm.executableName("odiff"))
  }

  @Test
  fun `downloads the pinned asset, verifies it, makes it executable and runs it once to check`() {
    val downloader = CountingDownloader(fakeBinary)
    val logs = mutableListOf<String>()

    val binary = installer(downloader, logs = logs).ensure()

    assertEquals(File(dir, "bin/odiff"), binary)
    assertTrue(binary.canExecute())
    assertEquals(listOf("https://example.test/v1/odiff-macos-arm64"), downloader.urls)
    assertFalse(File(dir, "bin/odiff-macos-arm64.part").exists(), "no partial file is left behind")
    assertTrue(logs.any { "Installing odiff" in it } && logs.any { "Installed odiff" in it }, logs.toString())
  }

  @Test
  fun `an installed binary is reused without downloading again`() {
    val downloader = CountingDownloader(fakeBinary)
    val i = installer(downloader)

    i.ensure()
    i.ensure()
    installer(downloader).ensure()

    assertEquals(1, downloader.calls.get())
    assertNotNull(i.installed())
  }

  @Test
  fun `a download that does not match the pinned checksum is rejected and removed`() {
    val downloader = CountingDownloader("something else".toByteArray())

    val e = assertFailsWith<MedusaException> { installer(downloader).ensure() }

    assertTrue("pinned checksum" in e.message!! && "Not installed" in e.message!!, e.message)
    assertFalse(File(dir, "bin/odiff").exists())
    assertFalse(File(dir, "bin/odiff-macos-arm64.part").exists())
  }

  @Test
  fun `a failed download is an error and leaves nothing behind`() {
    val e = assertFailsWith<MedusaException> { installer(CountingDownloader(fakeBinary, fail = true)).ensure() }

    assertTrue("HTTP 404" in e.message!!, e.message)
    assertNull(installer(CountingDownloader(fakeBinary)).installed())
  }

  @Test
  fun `a binary that downloads fine but cannot run here is removed`() {
    val broken = "not a program".toByteArray()
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(broken).joinToString("") { "%02x".format(it) }

    val e = assertFailsWith<MedusaException> { installer(CountingDownloader(broken), catalog = mapOf("odiff-macos-arm64" to sha)).ensure() }

    assertTrue("does not run on this machine" in e.message!!, e.message)
    assertFalse(File(dir, "bin/odiff").exists())
  }

  @Test
  fun `a platform without a prebuilt binary explains what to do`() {
    val downloader = CountingDownloader(fakeBinary)

    val e = assertFailsWith<MedusaException> { installer(downloader, platform = Platform(Os.LINUX, Arch.OTHER)).ensure() }

    assertTrue("publishes no prebuilt binary for linux-other" in e.message!!, e.message)
    assertEquals(0, downloader.calls.get())
  }

  @Test
  fun `many threads installing at once download only once`() {
    val downloader = CountingDownloader(fakeBinary)
    val pool = Executors.newFixedThreadPool(6)

    val results = (1..6).map { pool.submit<File> { installer(downloader).ensure() } }.map { it.get(30, TimeUnit.SECONDS) }
    pool.shutdownNow()

    assertEquals(1, downloader.calls.get())
    assertEquals(1, results.toSet().size)
  }

  @Test
  fun `a refused connection is explained, not reported as null`() {
    val target = File(dir, "never")

    // Port 1 on localhost refuses connections, so this needs no internet.
    val e = assertFailsWith<MedusaException> { HttpDownloader.download("http://127.0.0.1:1/odiff", target) }

    assertFalse("null" in e.message!!, e.message)
    assertTrue(e.message!!.startsWith("Downloading http://127.0.0.1:1/odiff failed: "), e.message)
    assertFalse(target.exists())
  }

  @Test
  fun `sha256 of a file`() {
    val f = File(dir, "x").also { it.writeText("abc") }
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", OdiffInstaller.sha256(f))
  }
}
