package dev.medusa.core.setup

import dev.medusa.core.MedusaException
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the `odiff` binary the way the Android CLI installs itself: for the current platform, into the user's home,
 * with no sudo and no package manager.
 *
 * The release is **pinned** ([VERSION]) and every asset's SHA-256 is pinned too (taken from the release's published
 * digests), so what is installed is a known file, not whatever "latest" is today. A mismatching download is deleted and
 * reported. The binary goes to `~/.medusa/bin/odiff`; an `odiff` that is already on PATH is used instead and never replaced.
 */
class OdiffInstaller(
  val installDir: File = defaultInstallDir(),
  private val platform: Platform = Platform.current(),
  private val downloader: Downloader = HttpDownloader,
  /** Asset name -> SHA-256. Tests replace it. */
  private val catalog: Map<String, String> = PINNED_SHA256,
  private val baseUrl: String = "https://github.com/dmtrKovalenko/odiff/releases/download/v$VERSION",
  private val log: (String) -> Unit = {},
) {
  val target: File get() = File(installDir, platform.executableName("odiff"))

  /** The release asset for this platform, or null where odiff publishes none (for example riscv64). */
  fun assetName(): String? = when (platform.os) {
    Os.MACOS -> "odiff-macos-${platform.arch.suffix() ?: return null}"
    Os.LINUX -> "odiff-linux-${platform.arch.suffix() ?: return null}"
    Os.WINDOWS -> "odiff-windows-${platform.arch.suffix() ?: return null}.exe"
    Os.OTHER -> null
  }?.takeIf { it in catalog }

  fun installed(): File? = target.takeIf { it.isFile && it.canExecute() }

  /** Returns the installed binary, downloading and verifying it first if needed. Safe to call concurrently, also across processes. */
  fun ensure(): File {
    installed()?.let { return it }
    val asset = assetName() ?: throw MedusaException(
      "odiff publishes no prebuilt binary for $platform. Install it yourself (https://github.com/dmtrKovalenko/odiff) " +
        "and put it on PATH, or set medusa { odiff { binary = \"...\" } }",
    )
    installDir.mkdirs()
    synchronized(LOCK) {
      FileChannel.open(File(installDir, ".install.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use {
          installed()?.let { return it } // another process finished while this one waited
          return install(asset)
        }
      }
    }
  }

  private fun install(asset: String): File {
    val part = File(installDir, "$asset.part")
    val url = "$baseUrl/$asset"
    log("Installing odiff ${VERSION} for $platform from $url")
    try {
      downloader.download(url, part)
      val actual = sha256(part)
      val expected = catalog.getValue(asset)
      if (!actual.equals(expected, ignoreCase = true)) {
        throw MedusaException("The downloaded $asset does not match the pinned checksum (expected $expected, got $actual). Not installed.")
      }
      part.setExecutable(true, false)
      Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      target.setExecutable(true, false)
      checkRuns(target)
      log("Installed odiff at ${target.path}")
      return target
    } catch (e: IOException) {
      throw MedusaException("Installing odiff into ${installDir.path} failed: ${e.message}", e)
    } finally {
      part.delete()
    }
  }

  /** A binary that downloads fine but cannot run here (wrong libc, blocked by the OS) must not be left behind. */
  private fun checkRuns(binary: File) {
    val ok = try {
      val process = ProcessBuilder(binary.path, "--version").redirectErrorStream(true).start()
      process.inputStream.readBytes()
      process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: IOException) {
      false
    }
    if (!ok) {
      binary.delete()
      throw MedusaException("The installed odiff does not run on this machine (${binary.path} --version failed), so it was removed")
    }
  }

  companion object {
    const val VERSION = "4.5.0"
    private val LOCK = Any()

    /** SHA-256 digests published with the odiff v4.5.0 release. */
    val PINNED_SHA256: Map<String, String> = mapOf(
      "odiff-macos-arm64" to "3c681171c158f95e7e62d636ddd00c33e8f971c23c85239c6192b72d76ad665b",
      "odiff-macos-x64" to "73e565e2a777b653fa0ceb90c138dec1c396c990913fdc1221fe8b01fa70c171",
      "odiff-linux-x64" to "2eb5a7f44df925d93b451898de8bf213f0aac270a9781187746097ed7cd45324",
      "odiff-linux-arm64" to "245ad45851da200cfdd361374618fca22781919faaca255aed1c93b883beb476",
      "odiff-windows-x64.exe" to "d2d38dfbdeff70c6d6a2f48ea41c9fe702f866c1f836c6896a677bf97652e4f1",
      "odiff-windows-arm64.exe" to "8fbc0d41d92ddee7790691a61e175845878824cc3851e9c69fe5d630223897d7",
    )

    fun defaultInstallDir(): File = File(System.getProperty("user.home"), ".medusa/bin")

    private fun Arch.suffix() = when (this) {
      Arch.ARM64 -> "arm64"
      Arch.X64 -> "x64"
      Arch.OTHER -> null
    }

    fun sha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }
  }
}
