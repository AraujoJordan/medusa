package dev.medusa.core.setup

import dev.medusa.core.AndroidCliLocator
import dev.medusa.core.MedusaException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Installs the Android CLI with Google's own installer for the platform
 * (https://developer.android.com/tools/agents/android-cli/archive), the same `curl | bash` the download page shows,
 * for the current user only (no sudo): `~/.local/bin/android` on macOS and Linux.
 *
 * Unlike [OdiffInstaller] there is no pinned checksum, because Google's script is the source of truth and updates itself,
 * so this runs **only when asked** (`./gradlew medusaSetup`), never as a side effect of verifying previews.
 */
class AndroidCliInstaller(
  private val platform: Platform = Platform.current(),
  private val downloader: Downloader = HttpDownloader,
  private val runScript: (File) -> Pair<Int, String> = ::runInstaller,
  private val log: (String) -> Unit = {},
) {
  /** The official installer for this platform, or null where Google publishes none. */
  fun installerUrl(): String? {
    val dir = when {
      platform.os == Os.MACOS && platform.arch == Arch.ARM64 -> "darwin_arm64"
      platform.os == Os.MACOS && platform.arch == Arch.X64 -> "darwin_x86_64"
      platform.os == Os.LINUX && platform.arch == Arch.X64 -> "linux_x86_64"
      platform.os == Os.LINUX && platform.arch == Arch.ARM64 -> "linux_arm64"
      platform.os == Os.WINDOWS && platform.arch == Arch.X64 -> "windows_x86_64"
      else -> return null
    }
    val script = if (platform.os == Os.WINDOWS) "install.cmd" else "install.sh"
    return "https://dl.google.com/android/cli/latest/$dir/$script"
  }

  /** The CLI if it is already installed, else installs it. Returns the binary. */
  fun ensure(path: String? = System.getenv("PATH"), home: String = System.getProperty("user.home")): File {
    runCatching { AndroidCliLocator.locate(path = path, home = home) }.getOrNull()?.let { return it }
    val url = installerUrl() ?: throw MedusaException(
      "Google publishes no Android CLI installer for $platform. See https://developer.android.com/tools/agents",
    )
    val script = File.createTempFile("android-cli-install", if (platform.os == Os.WINDOWS) ".cmd" else ".sh")
    try {
      log("Installing the Android CLI with Google's installer: $url")
      downloader.download(url, script)
      script.setExecutable(true)
      val (exit, output) = runScript(script)
      if (exit != 0) {
        // The script installs the binary and then runs it once to initialise itself. If that last step fails, the install
        // itself may still have worked (seen once on a fresh Linux container), so judge by whether the CLI runs.
        val installed = runCatching { AndroidCliLocator.locate(path = path, home = home) }.getOrNull()
        if (installed == null || !runs(installed)) {
          throw MedusaException("Google's Android CLI installer failed (exit $exit):\n$output")
        }
        log("Google's installer exited with $exit after installing, but ${installed.path} runs, so it is used")
      }
    } finally {
      script.delete()
    }
    return runCatching { AndroidCliLocator.locate(path = path, home = home) }.getOrElse {
      throw MedusaException(
        "The Android CLI installer finished but no `android` binary was found. Open a new shell or add ~/.local/bin to PATH.",
        it,
      )
    }
  }

  companion object {
    private fun runs(binary: File): Boolean = try {
      val process = ProcessBuilder(binary.path, "-V").redirectErrorStream(true).start()
      process.inputStream.readBytes()
      process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: IOException) {
      false
    }

    private fun runInstaller(script: File): Pair<Int, String> {
      val command = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
        listOf("cmd", "/c", script.path)
      } else {
        listOf("bash", script.path)
      }
      return try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
          process.destroyForcibly()
          throw MedusaException("Google's Android CLI installer did not finish within 5 minutes")
        }
        process.exitValue() to output
      } catch (e: IOException) {
        throw MedusaException("Could not run the Android CLI installer: ${e.message}", e)
      }
    }
  }
}
