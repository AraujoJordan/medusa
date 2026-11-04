package dev.medusa.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class MedusaExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Share of pixels (0-100) allowed to differ from the golden. Default 0.01, like Paparazzi.
   * Also settable with `-Pmedusa.maxPercentDifference=`.
   */
  abstract val maxPercentDifference: Property<Double>

  /**
   * Which algorithm decides: `odiff` (default; Medusa installs it when missing, see [autoInstall]), the in-process `yiq`
   * (odiff's algorithm, no binary), `magick` (ImageMagick `compare`), or Paparazzi's `offbytwo`, `pixelperfect`, `mssim`,
   * `sift`, `flip`, `de2000`. Also settable with `-Pmedusa.differ=`.
   */
  abstract val differ: Property<String>

  /**
   * Download odiff (a pinned release, SHA-256 verified) into [installDir] the first time it is needed and not found.
   * Turn it off for builds that must not touch the network; `-Pmedusa.autoInstall=false`.
   */
  abstract val autoInstall: Property<Boolean>

  /**
   * If odiff is neither found nor installable (offline, unsupported platform), compare with the built-in `yiq` differ and
   * warn, instead of failing the build. `-Pmedusa.fallbackToYiq=false` to fail.
   */
  abstract val fallbackToYiq: Property<Boolean>

  /** Where Medusa installs tools. Default `~/.medusa/bin`. `-Pmedusa.installDir=` */
  abstract val installDir: Property<String>

  /** Options of `differ = "yiq"`. */
  val yiq: YiqDsl = objects.newInstance(YiqDsl::class.java)
  fun yiq(action: Action<in YiqDsl>) = action.execute(yiq)

  /** Options of `differ = "odiff"`. Needs the `odiff` binary (`brew install odiff`). */
  val odiff: OdiffDsl = objects.newInstance(OdiffDsl::class.java)
  fun odiff(action: Action<in OdiffDsl>) = action.execute(odiff)

  /** Options of `differ = "magick"`. Needs ImageMagick (`brew install imagemagick`). */
  val magick: MagickDsl = objects.newInstance(MagickDsl::class.java)
  fun magick(action: Action<in MagickDsl>) = action.execute(magick)

  /** Concurrent `android preview render` processes. Each pays the CLI's fixed start-up cost, so 1 is usually fastest. Also `-Pmedusa.renderParallelism=`. */
  abstract val renderParallelism: Property<Int>

  /** How many images are compared at the same time while rendering continues. Default: number of CPU cores. Also `-Pmedusa.compareParallelism=`. */
  abstract val compareParallelism: Property<Int>

  /** Also record and verify the accessibility tree of every preview (`<golden>.semantics.txt`). Also `-Pmedusa.accessibilitySnapshots=true`. */
  abstract val accessibilitySnapshots: Property<Boolean>

  /** Path to the `android` binary. Default: looked up on PATH, then `~/.local/bin`. */
  abstract val androidCliPath: Property<String>

  /** Also render previews from the modules this module depends on (`android preview list --all`). Also `-Pmedusa.includeDependentModules=true`. */
  abstract val includeDependentModules: Property<Boolean>

  /** Keep the CLI's preview renderer running between invocations (`--daemon`, default true; expires after 10 idle minutes). Also `-Pmedusa.useDaemon=true`. */
  abstract val useDaemon: Property<Boolean>

  /** Aggregate compatible uniquely-owned libraries through their app for root commands and check. */
  abstract val autoAggregate: Property<Boolean>

  /** Whole-process deadlines, in seconds. */
  abstract val discoveryTimeoutSeconds: Property<Long>
  abstract val renderTimeoutSeconds: Property<Long>
  abstract val comparisonTimeoutSeconds: Property<Long>
  /** Optional visual rerenders. Zero keeps ordinary verification strict and fast. */
  abstract val visualRetryCount: Property<Int>
  /** Permit a passing visual retry to recover the verdict, reporting it as flaky. */
  abstract val allowRecoveredPass: Property<Boolean>

  /** Run medusaVerify as part of `check`. */
  abstract val verifyOnCheck: Property<Boolean>

  /** Directories searched for @Preview sources. Default: the debug variant's Java/Kotlin sources as reported by AGP. */
  abstract val sourceDirs: ConfigurableFileCollection
}

abstract class YiqDsl {
  /** 0..1; 0.1 is odiff's and pixelmatch's default and hides small colour drift. 0 flags any change. `-Pmedusa.yiq.threshold=` */
  abstract val threshold: Property<Double>

  /** Do not count antialiased edge pixels. `-Pmedusa.yiq.antialiasing=true` */
  abstract val antialiasing: Property<Boolean>
}

abstract class OdiffDsl {
  /** Path or name of the `odiff` binary. Default: looked up on PATH. `-Pmedusa.odiff.binary=` */
  abstract val binary: Property<String>

  abstract val threshold: Property<Double>

  abstract val antialiasing: Property<Boolean>

  /** Rectangles to skip, `"x1:y1-x2:y2"` (e.g. a clock). `-Pmedusa.odiff.ignoreRegions=0:0-100:24,...` */
  abstract val ignoreRegions: ListProperty<String>

  /** Use long-running `odiff --server` processes (default) or one process per image. `-Pmedusa.odiff.useServer=false` */
  abstract val useServer: Property<Boolean>
}

abstract class MagickDsl {
  /** Path or name of the `magick` binary. Default: looked up on PATH. `-Pmedusa.magick.binary=` */
  abstract val binary: Property<String>

  /** `AE` (default), `RMSE`, `MAE`, `DSSIM` or `PSNR`. `-Pmedusa.magick.metric=` */
  abstract val metric: Property<String>

  /** `-fuzz`, for example `"2%"` (default). Set to an empty string to not pass it. `-Pmedusa.magick.fuzz=` */
  abstract val fuzz: Property<String>

  /** For `PSNR`: the lowest decibel value that passes (default 40). `-Pmedusa.magick.minPsnr=` */
  abstract val minPsnr: Property<Double>
}
