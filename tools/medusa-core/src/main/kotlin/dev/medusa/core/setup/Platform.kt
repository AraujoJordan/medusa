package dev.medusa.core.setup

enum class Os { MACOS, LINUX, WINDOWS, OTHER }

enum class Arch { ARM64, X64, OTHER }

data class Platform(val os: Os, val arch: Arch) {
  /** `odiff` on macOS and Linux, `odiff.exe` on Windows. */
  fun executableName(name: String) = if (os == Os.WINDOWS) "$name.exe" else name

  override fun toString() = "${os.name.lowercase()}-${arch.name.lowercase()}"

  companion object {
    fun current(osName: String = System.getProperty("os.name"), osArch: String = System.getProperty("os.arch")): Platform {
      val os = when {
        osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) -> Os.MACOS
        osName.contains("linux", ignoreCase = true) -> Os.LINUX
        osName.contains("windows", ignoreCase = true) -> Os.WINDOWS
        else -> Os.OTHER
      }
      val arch = when (osArch.lowercase()) {
        "aarch64", "arm64" -> Arch.ARM64
        "amd64", "x86_64", "x64" -> Arch.X64
        else -> Arch.OTHER
      }
      return Platform(os, arch)
    }
  }
}
