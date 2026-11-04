package dev.medusa.core.compare

import dev.medusa.core.Differs
import dev.medusa.core.MedusaException
import dev.medusa.core.setup.OdiffInstaller
import java.io.File

object ImageComparers {
  /** Every value `differ` accepts: Paparazzi's six, the in-process `yiq`, and the external `odiff` and `magick`. */
  val names: List<String> = Differs.names + listOf("yiq", "odiff", "magick")

  /** Validate options without downloading tools or starting comparison processes. */
  fun validate(spec: ComparerSpec) {
    require(spec.comparisonTimeoutSeconds > 0) { "Comparison timeout must be positive" }
    val name = spec.differ.lowercase()
    if (name !in names + listOf("", "default")) throw MedusaException("Unknown differ '${spec.differ}'. Use one of: ${names.joinToString()}")
    require(spec.maxPercentDifference in 0.0..100.0) { "maxPercentDifference must be between 0 and 100" }
    when (name) {
      "odiff", "", "default" -> {
        require(spec.odiff.threshold in 0.0..1.0) { "odiff threshold must be between 0 and 1" }
        spec.odiff.ignoreRegions.forEach(Region::parse)
        if (spec.odiff.binary != null) OdiffImageComparer.locate(spec.odiff.binary)
      }
      "yiq" -> require(spec.yiq.threshold in 0.0..1.0) { "yiq threshold must be between 0 and 1" }
      "magick" -> {
        require(spec.magick.metric.uppercase() in listOf("AE", "RMSE", "MAE", "DSSIM", "PSNR")) { "Unsupported ImageMagick metric '${spec.magick.metric}'" }
        if (spec.magick.binary != null) MagickImageComparer.locate(spec.magick.binary)
      }
    }
  }

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
          timeoutSeconds = spec.comparisonTimeoutSeconds,
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
      timeoutSeconds = spec.comparisonTimeoutSeconds,
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
    if (spec.odiff.ignoreRegions.isNotEmpty()) throw MedusaException("$reason. Cannot fall back to yiq with configured ignore regions; install odiff or remove the regions.")
    notice("WARNING: $reason. Comparing with the built-in `yiq` differ instead, which counts the same differing pixels as odiff. $hint")
    return null
  }
}
