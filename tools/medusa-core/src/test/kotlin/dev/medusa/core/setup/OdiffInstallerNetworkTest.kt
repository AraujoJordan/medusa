package dev.medusa.core.setup

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Downloads the real odiff release for this machine and checks it against the pinned checksum. Off by default (it needs
 * the network); run it with `MEDUSA_NETWORK_TESTS=1 ./gradlew test`, and before bumping [OdiffInstaller.VERSION].
 */
class OdiffInstallerNetworkTest {
  @Test
  fun `the pinned checksum matches the real release asset and the binary runs`() {
    assumeTrue(System.getenv("MEDUSA_NETWORK_TESTS") == "1", "set MEDUSA_NETWORK_TESTS=1 to download odiff")
    val dir = createTempDirectory("odiff-real").toFile()
    val logs = mutableListOf<String>()

    val binary = OdiffInstaller(dir, log = { logs += it }).ensure()

    assertTrue(binary.canExecute(), logs.toString())
    val version = ProcessBuilder(binary.path, "--version").redirectErrorStream(true).start().let { it.inputStream.bufferedReader().readText().also { _ -> it.waitFor() } }
    assertTrue(OdiffInstaller.VERSION in version, "odiff --version said: $version")
    assertEquals(File(dir, binary.name), binary)
  }
}
