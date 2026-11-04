package dev.medusa.core.compare

import dev.medusa.core.MedusaException
import java.io.File

internal object BinaryLocator {
  val DEFAULT_SYSTEM_DIRS = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")

  /** Looks on PATH, then in [extraDirs] (for example `~/.medusa/bin`), then in the usual Homebrew and system directories. */
  fun find(name: String, path: String?, extraDirs: List<File> = emptyList(), systemDirs: List<String> = DEFAULT_SYSTEM_DIRS): File? {
    val names = listOf(name, "$name.exe")
    val pathDirs = path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)
    return (pathDirs + extraDirs + systemDirs.map(::File))
      .flatMap { dir -> names.map { File(dir, it) } }
      .firstOrNull { it.isFile && it.canExecute() }
  }

  /** A configured path must exist; otherwise the binary is looked up with [find]. */
  fun locate(
    name: String,
    explicit: String?,
    path: String?,
    installHint: String,
    extraDirs: List<File> = emptyList(),
    systemDirs: List<String> = DEFAULT_SYSTEM_DIRS,
  ): File {
    if (explicit != null) {
      val file = File(explicit)
      if (file.isFile && file.canExecute()) return file
      // A bare name such as "magick" is looked up on PATH like a shell would.
      if (!explicit.contains(File.separatorChar)) {
        find(explicit, path, systemDirs = systemDirs)?.let { return it }
      }
      throw MedusaException("$name not found at the configured path: $explicit")
    }
    return find(name, path, extraDirs, systemDirs)
      ?: throw MedusaException("$name was selected as the differ but is not installed. Install it with: $installHint")
  }
}
