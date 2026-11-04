package dev.medusa.gradle

import dev.medusa.core.AndroidCliBackend
import dev.medusa.core.AndroidCliLocator
import dev.medusa.core.compare.ComparerSpec
import dev.medusa.core.compare.ImageComparer
import dev.medusa.core.compare.ImageComparers
import dev.medusa.core.MedusaConfig
import dev.medusa.core.MedusaException
import dev.medusa.core.MedusaRunner
import dev.medusa.core.Outcome
import dev.medusa.core.PreviewFilter
import dev.medusa.core.RecordStatus
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

@UntrackedTask(because = "Runs the Android CLI, which builds the project in its own Gradle invocation")
abstract class MedusaTask : DefaultTask() {
  @get:Internal
  @get:Option(option = "preview", description = "Only this preview: FQN, Class.Name, simple name or a glob (*, ?). Repeatable.")
  abstract val previewFilter: ListProperty<String>

  @get:Internal abstract val rootDirectory: DirectoryProperty

  /** The module's Gradle path (for example `:feature:home`), the reference the Android CLI resolves for every module. */
  @get:Internal abstract val moduleRef: Property<String>

  /** True when the task was requested for all modules at once, so a `--preview` filter may legitimately match nothing here. */
  @get:Internal abstract val skipWhenNoPreviewMatches: Property<Boolean>

  @get:Internal abstract val sourceRoots: ConfigurableFileCollection

  @get:Internal abstract val medusaOutputDir: DirectoryProperty

  @get:Internal abstract val comparerSpec: Property<ComparerSpec>

  @get:Internal abstract val androidCliPath: Property<String>

  @get:Internal abstract val includeDependentModules: Property<Boolean>

  @get:Internal abstract val useDaemon: Property<Boolean>

  @get:Internal abstract val renderParallelism: Property<Int>

  @get:Internal abstract val compareParallelism: Property<Int>

  @get:Internal abstract val accessibilitySnapshots: Property<Boolean>

  init {
    group = "medusa"
  }

  protected fun newRunner(comparer: ImageComparer): MedusaRunner {
    val cli = try {
      AndroidCliLocator.locate(explicit = androidCliPath.orNull)
    } catch (e: MedusaException) {
      throw GradleException(e.message!!, e)
    }
    val accessibility = accessibilitySnapshots.getOrElse(false)
    val backend = AndroidCliBackend(
      workingDir = rootDirectory.get().asFile,
      moduleRef = moduleRef.get(),
      androidCli = cli,
      includeDependentModules = includeDependentModules.getOrElse(false),
      daemon = useDaemon.getOrElse(false),
      componentTree = accessibility,
      log = { logger.info(it) },
    )
    return MedusaRunner(
      MedusaConfig(
        backend = backend,
        sourceRoots = sourceRoots.files.toList(),
        outputDir = medusaOutputDir.get().asFile,
        comparer = comparer,
        filter = PreviewFilter(previewFilter.getOrElse(emptyList())),
        renderParallelism = renderParallelism.getOrElse(1),
        compareParallelism = compareParallelism.getOrElse(Runtime.getRuntime().availableProcessors()),
        accessibility = accessibility,
        failOnNoMatch = !skipWhenNoPreviewMatches.getOrElse(false),
        log = { logger.info(it) },
        notice = { logger.lifecycle("$path: $it") },
      ),
    )
  }

  @TaskAction
  fun execute() {
    try {
      val spec = comparerSpec.get()
      // Closing stops the `odiff --server` processes, if that is what compares.
      val parallelism = compareParallelism.getOrElse(Runtime.getRuntime().availableProcessors())
      ImageComparers.create(spec, File(medusaOutputDir.get().asFile, "compare"), parallelism, notice = { logger.lifecycle(it) }).use {
        run(newRunner(it))
      }
    } catch (e: MedusaException) {
      throw GradleException(e.message!!, e)
    }
  }

  protected abstract fun run(runner: MedusaRunner)
}

abstract class MedusaListTask : MedusaTask() {
  init {
    description = "Lists the @Preview functions Medusa tests and where their goldens live."
  }

  override fun run(runner: MedusaRunner) {
    val discovery = runner.discover()
    logger.lifecycle("Source roots searched:")
    sourceRoots.files.sortedBy { it.path }.forEach { logger.lifecycle("    ${it.path}${if (it.isDirectory) "" else "  (does not exist)"}") }
    discovery.snapshots.forEach {
      val kind = when {
        it.preview.hasParameterProvider -> " (parameterized)"
        it.entryCount > 1 -> " (multipreview, ${it.entryCount} images)"
        else -> ""
      }
      logger.lifecycle("${it.preview.methodFqn}$kind")
      logger.lifecycle("    ${it.goldenFile(0).path}")
    }
    discovery.unlocated.forEach { logger.warn("${it.methodFqn}: source file not found") }
    logger.lifecycle("${discovery.snapshots.size} preview(s)")
  }
}

abstract class MedusaRecordTask : MedusaTask() {
  init {
    description = "Renders previews and saves them as the new golden images next to their source files."
  }

  override fun run(runner: MedusaRunner) = record(runner)
}

abstract class MedusaCleanRecordTask : MedusaTask() {
  init {
    description = "Deletes the golden images, then records them again."
  }

  override fun run(runner: MedusaRunner) {
    val deleted = runner.delete()
    logger.lifecycle("Deleted ${deleted.count { it.extension == "png" }} golden image(s)")
    record(runner)
  }
}

abstract class MedusaDeleteTask : MedusaTask() {
  init {
    description = "Deletes the golden images of the selected previews."
  }

  override fun run(runner: MedusaRunner) {
    val deleted = runner.delete()
    deleted.forEach { logger.lifecycle("Deleted ${it.path}") }
    logger.lifecycle("Deleted ${deleted.count { it.extension == "png" }} golden image(s)")
  }
}

abstract class MedusaVerifyTask : MedusaTask() {
  init {
    description = "Renders previews and compares them with the golden images."
  }

  override fun run(runner: MedusaRunner) {
    val summary = runner.verify()
    summary.orphans.forEach { logger.warn("Unused golden image (no preview produces it): ${it.path}") }
    if (summary.ok) {
      logger.lifecycle("Medusa: ${summary.results.size} preview(s) match their golden images")
      return
    }
    val lines = summary.failures.joinToString("\n") { f ->
      when (val o = f.outcome) {
        is Outcome.Missing -> "  ${f.name}: no golden image (${o.golden.name}). Run medusaRecord."
        is Outcome.Failed -> "  ${f.name}: ${o.message}"
        Outcome.Passed -> error("unreachable")
      }
    }
    throw GradleException(
      "Medusa: ${summary.failures.size} of ${summary.results.size} preview(s) failed:\n$lines\n" +
        "Report: ${summary.reportFile.toURI()}\nRun medusaRecord to accept the new rendering.",
    )
  }
}

internal fun MedusaTask.record(runner: MedusaRunner) {
  val summary = runner.record()
  summary.recorded.forEach {
    val tag = when (it.status) {
      RecordStatus.NEW -> "new      "
      RecordStatus.CHANGED -> "changed  "
      RecordStatus.UNCHANGED -> "unchanged"
    }
    logger.lifecycle("  $tag ${it.golden.path}")
  }
  val changed = summary.recorded.count { it.status != RecordStatus.UNCHANGED }
  logger.lifecycle("Medusa: recorded ${summary.recorded.size} preview(s), $changed written")
  if (!summary.ok) {
    throw GradleException("Medusa: ${summary.errors.size} preview(s) could not be recorded:\n" + summary.errors.joinToString("\n") { "  $it" })
  }
}
