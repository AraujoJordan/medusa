package dev.medusa.core.compare

import dev.medusa.core.Differs
import dev.medusa.core.MedusaException
import dev.medusa.core.setup.OdiffInstaller
import java.io.File

object ImageComparers {
  /** Every value `differ` accepts: Paparazzi's six, the in-process `yiq`, and the external `odiff` and `magick`. */
  val names: List<String> = Differs.names + listOf("yiq", "odiff", "magick")

  /**
   * @param workDir where the external tools write their diff images (temporary)
   * @param parallelism how many comparisons run at once, which sizes the `odiff --server` pool
   * @param notice shown to the user: odiff being installed, or a fallback to `yiq`
   * @param installer replaces the default odiff installer (tests)
   */
  fun create(
    spec: ComparerSpec,
    workDir: File,
    parallelism: Int = Runtime.getRuntime().availableProcessors(),
    path: String? = System.getenv("PATH"),
    notice: (String) -> Unit = {},
    installer: OdiffInstaller? = null,
    systemDirs: List<String> = BinaryLocator.DEFAULT_SYSTEM_DIRS,
  ): ImageComparer = when (val name = spec.differ.lowercase()) {
    "yiq" -> yiq(spec)
    "odiff", "", "default" -> {
      val binary = resolveOdiff(spec, path, notice, installer, systemDirs)
      if (binary == null) {
        yiq(spec)
      } else {
        OdiffImageComparer(
          binary = binary,
          threshold = spec.odiff.threshold,
          antialiasing = spec.odiff.antialiasing,
          ignoreRegions = spec.odiff.ignoreRegions,
          maxPercentDifference = spec.maxPercentDifference,
          workDir = workDir,
          parallelism = parallelism,
          useServer = spec.odiff.useServer,
        )
      }
    }
    "magick" -> MagickImageComparer(
      binary = MagickImageComparer.locate(spec.magick.binary, path),
      metric = spec.magick.metric,
      fuzz = spec.magick.fuzz,
      minPsnr = spec.magick.minPsnr,
      maxPercentDifference = spec.maxPercentDifference,
      workDir = workDir,
    )
    in Differs.names -> PaparazziImageComparer(Differs.byName(name), spec.maxPercentDifference)
    else -> throw MedusaException("Unknown differ '${spec.differ}'. Use one of: ${names.joinToString()}")
  }

  /** `yiq` with the odiff options, so that falling back from odiff compares with the same threshold. */
  private fun yiq(spec: ComparerSpec): ImageComparer {
    val usesOdiffOptions = spec.differ.lowercase() != "yiq"
    val options = if (usesOdiffOptions) YiqOptions(spec.odiff.threshold, spec.odiff.antialiasing) else spec.yiq
    return PaparazziImageComparer(YiqDiffer(options.threshold, options.antialiasing), spec.maxPercentDifference)
  }

  /**
   * The odiff binary, or null when it cannot be had and falling back is allowed.
   * A path that was configured explicitly is a decision and is never replaced: if it is wrong, that is an error.
   * With `fallbackToYiq = false` this either returns a binary or throws, which is what `medusaSetup` uses.
   */
  fun resolveOdiff(
    spec: ComparerSpec,
    path: String?,
    notice: (String) -> Unit,
    installer: OdiffInstaller? = null,
    systemDirs: List<String> = BinaryLocator.DEFAULT_SYSTEM_DIRS,
  ): File? {
    val installDir = spec.installDir?.let(::File) ?: OdiffInstaller.defaultInstallDir()
    if (spec.odiff.binary != null) return OdiffImageComparer.locate(spec.odiff.binary, path, installDir, systemDirs)
    BinaryLocator.find("odiff", path, listOf(installDir), systemDirs)?.let { return it }

    val reason = if (!spec.autoInstall) {
      "odiff is not installed and medusa.autoInstall is off"
    } else {
      try {
        return (installer ?: OdiffInstaller(installDir, log = notice)).ensure()
      } catch (e: MedusaException) {
        e.message ?: "odiff could not be installed"
      }
    }
    val hint = "Install it with `./gradlew medusaSetup` or `brew install odiff`."
    if (!spec.fallbackToYiq) throw MedusaException("$reason. $hint")
    notice("WARNING: $reason. Comparing with the built-in `yiq` differ instead, which counts the same differing pixels as odiff. $hint")
    return null
  }
}
