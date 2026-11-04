package dev.medusa.gradle

import dev.medusa.core.AndroidCliLocator
import dev.medusa.core.MedusaException
import dev.medusa.core.compare.MagickImageComparer
import dev.medusa.core.compare.ComparerSpec
import dev.medusa.core.compare.ImageComparers
import dev.medusa.core.setup.AndroidCliInstaller
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * `./gradlew medusaSetup`: checks what Medusa needs on this machine and installs what is missing, for the current platform
 * and the current user (no sudo): the Android CLI (with Google's own installer) and, if it is the selected differ, odiff
 * (a pinned release with a verified checksum). Safe to run again; it only reports what is already there.
 */
@UntrackedTask(because = "Installs tools into the user's home directory")
abstract class MedusaSetupTask : DefaultTask() {
  @get:Internal abstract val androidCliPath: Property<String>

  @get:Internal abstract val comparerSpec: Property<ComparerSpec>

  @get:Internal abstract val concurrency: Property<String>

  init {
    group = "medusa"
    description = "Checks the Android CLI and the selected differ (odiff by default) and installs what is missing."
  }

  @TaskAction
  fun setup() {
    try {
      val explicit = androidCliPath.orNull
      val cli = if (explicit != null) {
        AndroidCliLocator.locate(explicit = explicit)
      } else {
        AndroidCliInstaller(log = { logger.lifecycle(it) }).ensure()
      }
      logger.lifecycle("Android CLI: ${cli.path}")
      logger.lifecycle("Concurrency: ${concurrency.get()}")

      val spec = comparerSpec.get()
      when (val differ = spec.differ.lowercase()) {
        "odiff", "", "default" -> {
          val odiff = ImageComparers.resolveOdiff(spec, System.getenv("PATH"), { logger.lifecycle(it) })
          logger.lifecycle("odiff: ${odiff?.path}")
        }
        "magick" -> logger.lifecycle("magick: ${MagickImageComparer.locate(spec.magick.binary).path}")
        else -> logger.lifecycle("Differ '$differ' is built in, nothing to install.")
      }
    } catch (e: MedusaException) {
      throw GradleException(e.message!!, e)
    }
  }
}
