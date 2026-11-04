package dev.medusa.core.setup

import dev.medusa.core.MedusaException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCliInstallerTest {
  private val home = createTempDirectory("fake-home").toFile()

  private fun installer(platform: Platform, downloader: Downloader = Downloader { _, f -> f.writeText("#!/bin/sh\n") }, run: (File) -> Pair<Int, String>) =
    AndroidCliInstaller(platform, downloader, run)

  private fun cliPath() = File(home, ".local/bin/android")

  @Test
  fun `uses Google's official installer for the platform`() {
    fun url(os: Os, arch: Arch) = AndroidCliInstaller(Platform(os, arch)).installerUrl()

    assertEquals("https://dl.google.com/android/cli/latest/darwin_arm64/install.sh", url(Os.MACOS, Arch.ARM64))
    assertEquals("https://dl.google.com/android/cli/latest/darwin_x86_64/install.sh", url(Os.MACOS, Arch.X64))
    assertEquals("https://dl.google.com/android/cli/latest/linux_x86_64/install.sh", url(Os.LINUX, Arch.X64))
    assertEquals("https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd", url(Os.WINDOWS, Arch.X64))
    assertEquals("https://dl.google.com/android/cli/latest/linux_arm64/install.sh", url(Os.LINUX, Arch.ARM64), "published, though the download page does not list it")
    assertNull(url(Os.WINDOWS, Arch.ARM64), "no Windows arm64 installer exists (404)")
  }

  @Test
  fun `an installed Android CLI is left alone`() {
    cliPath().also { it.parentFile.mkdirs(); it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
    var ran = false

    val found = installer(Platform(Os.MACOS, Arch.ARM64)) { ran = true; 0 to "" }.ensure(path = "", home = home.path)

    assertEquals(cliPath(), found)
    assertTrue(!ran)
  }

  @Test
  fun `a missing Android CLI is installed by running the downloaded script`() {
    val urls = mutableListOf<String>()
    val downloader = Downloader { url, file -> urls += url; file.writeText("#!/bin/sh\n") }

    val found = installer(Platform(Os.MACOS, Arch.ARM64), downloader) { script ->
      assertTrue(script.canExecute() && script.name.endsWith(".sh"))
      cliPath().also { it.parentFile.mkdirs(); it.writeText("#!/bin/sh\n"); it.setExecutable(true) } // what Google's script does
      0 to "Success"
    }.ensure(path = "", home = home.path)

    assertEquals(cliPath(), found)
    assertEquals(listOf("https://dl.google.com/android/cli/latest/darwin_arm64/install.sh"), urls)
  }

  @Test
  fun `a failing installer reports its output`() {
    val e = assertFailsWith<MedusaException> {
      installer(Platform(Os.LINUX, Arch.X64)) { 1 to "disk full" }.ensure(path = "", home = home.path)
    } // nothing was installed, so the exit code decides
    assertTrue("exit 1" in e.message!! && "disk full" in e.message!!, e.message)
  }

  @Test
  fun `an installer that fails after installing a working CLI is accepted`() {
    val logs = mutableListOf<String>()
    val i = AndroidCliInstaller(Platform(Os.LINUX, Arch.X64), Downloader { _, f -> f.writeText("#!/bin/sh\n") }, { _ ->
      cliPath().also { it.parentFile.mkdirs(); it.writeText("#!/bin/sh\necho 'android 1.1'\n"); it.setExecutable(true) }
      1 to "Error: No such file or directory (os error 2)" // the script's final first-run step failed
    }, { logs += it })

    val found = i.ensure(path = "", home = home.path)

    assertEquals(cliPath(), found)
    assertTrue(logs.any { "exited with 1 after installing" in it }, logs.toString())
  }

  @Test
  fun `a failing installer whose CLI does not run is still an error`() {
    val i = AndroidCliInstaller(Platform(Os.LINUX, Arch.X64), Downloader { _, f -> f.writeText("#!/bin/sh\n") }, { _ ->
      cliPath().also { it.parentFile.mkdirs(); it.writeText("#!/bin/sh\nexit 3\n"); it.setExecutable(true) }
      1 to "broken"
    })

    val e = assertFailsWith<MedusaException> { i.ensure(path = "", home = home.path) }

    assertTrue("exit 1" in e.message!! && "broken" in e.message!!, e.message)
  }

  @Test
  fun `an installer that finishes without producing the binary says where to look`() {
    val e = assertFailsWith<MedusaException> { installer(Platform(Os.LINUX, Arch.X64)) { 0 to "" }.ensure(path = "", home = home.path) }
    assertTrue("no `android` binary was found" in e.message!!, e.message)
  }

  @Test
  fun `a platform Google publishes nothing for is an error`() {
    val e = assertFailsWith<MedusaException> { installer(Platform(Os.WINDOWS, Arch.ARM64)) { 0 to "" }.ensure(path = "", home = home.path) }
    assertTrue("publishes no Android CLI installer for windows-arm64" in e.message!!, e.message)
  }
}
