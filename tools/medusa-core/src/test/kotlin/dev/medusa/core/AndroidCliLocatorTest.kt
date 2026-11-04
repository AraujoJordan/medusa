package dev.medusa.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidCliLocatorTest {
  private val dir = createTempDirectory("medusa-cli").toFile()

  private fun cli(path: String): File = File(dir, path).also {
    it.parentFile.mkdirs()
    it.writeText("#!/bin/sh\n")
    it.setExecutable(true)
  }

  @Test
  fun `explicit path wins`() {
    val explicit = cli("custom/android")
    cli("bin/android")

    assertEquals(explicit, AndroidCliLocator.locate(explicit.path, path = File(dir, "bin").path, home = dir.path))
  }

  @Test
  fun `explicit path that does not exist fails instead of using another binary`() {
    cli("bin/android")

    val e = assertFailsWith<MedusaException> {
      AndroidCliLocator.locate(File(dir, "nope/android").path, path = File(dir, "bin").path, home = dir.path)
    }
    assertTrue("androidCliPath" in e.message!!)
  }

  @Test
  fun `when nothing is configured and nothing is found the message offers medusaSetup`() {
    val e = assertFailsWith<MedusaException> { AndroidCliLocator.locate(path = "", home = File(dir, "nohome").path, systemDirs = emptyList()) }

    assertTrue("./gradlew medusaSetup" in e.message!! && "https://developer.android.com/tools/agents" in e.message!!, e.message)
  }

  @Test
  fun `found on PATH`() {
    val onPath = cli("bin/android")

    assertEquals(onPath, AndroidCliLocator.locate(path = File(dir, "bin").path, home = dir.path))
  }

  @Test
  fun `falls back to the user-local install directory`() {
    val local = cli("home/.local/bin/android")

    assertEquals(local, AndroidCliLocator.locate(path = "", home = File(dir, "home").path))
  }

  @Test
  fun `a non-executable file is skipped`() {
    File(dir, "bin").mkdirs()
    File(dir, "bin/android").writeText("not executable")

    assertFailsWith<MedusaException> {
      AndroidCliLocator.locate(path = File(dir, "bin").path, home = File(dir, "nohome").path, systemDirs = emptyList())
    }
  }
}
