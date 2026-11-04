package dev.medusa.gradle

import dev.medusa.core.*
import dev.medusa.core.compare.*
import java.io.File
import java.io.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

internal data class ModuleSettings(
  val cli: String?, val comparer: ComparerSpec, val daemon: Boolean,
  val renders: Int, val compares: Int, val accessibility: Boolean,
) : Serializable

internal data class GroupModule(
  val path: String, val sources: List<String>, val output: String,
  val settings: ModuleSettings, val manual: Boolean,
) : Serializable

@UntrackedTask(because = "The Android CLI builds and renders on each invocation")
abstract class MedusaAggregateTask : DefaultTask() {
  @get:Internal internal abstract val members: ListProperty<GroupModule>
  @get:Internal internal abstract val universe: ListProperty<GroupModule>
  @get:Internal abstract val workingDirectory: Property<String>
  @get:Internal abstract val host: Property<String>
  @get:Internal abstract val operation: Property<String>
  @get:Internal
  @get:Option(option = "preview", description = "Preview name or glob, repeatable")
  abstract val previewFilter: ListProperty<String>

  @TaskAction fun execute() {
    val modules = members.get()
    val owner = modules.single { it.path == host.get() }
    modules.forEach { validateComparer(it.settings.comparer) }
    fun backend(module: GroupModule, all: Boolean) = AndroidCliBackend(
      File(workingDirectory.get()), module.path, AndroidCliLocator.locate(explicit = module.settings.cli),
      includeDependentModules = all, daemon = module.settings.daemon,
      componentTree = module.settings.accessibility, log = { logger.info(it) },
    )
    if (modules.size == 1) {
      executeRun(owner, modules, backend(owner, owner.manual), emptyMap())
      return
    }
    val engine = backend(owner, true)
    val listed = engine.list()
    val indexes = universe.get().associate { it.path to SourceLocator.Index(it.sources.map(::File)) }
    val allowed = modules.map { it.path }.toSet()
    val selected = mutableListOf<PreviewInfo>()
    var ambiguous = false
    for (preview in listed.previews) {
      val owners = indexes.filterValues { it.locate(preview) != null }.keys
      if (owners.size != 1) { ambiguous = true; break }
      if (owners.single() in allowed) selected += preview
    }
    if (ambiguous) {
      logger.lifecycle("Medusa: ambiguous source ownership in ${owner.path}; rendering group members independently")
      modules.forEach { executeRun(it, listOf(it), backend(it, it.manual), emptyMap()) }
      return
    }
    val selectedBackend = object : PreviewBackend {
      override fun list() = listed.copy(previews = selected)
      override fun render(fqns: List<String>, outputDir: File) = engine.render(fqns, outputDir)
    }
    logger.lifecycle("Medusa: ${modules.joinToString { it.path }} rendered through ${owner.path}")
    val outputs = modules.flatMap { module -> module.sources.map { File(it) to File(module.output) } }.toMap()
    executeRun(owner, modules, selectedBackend, outputs)
  }

  private fun executeRun(owner: GroupModule, modules: List<GroupModule>, backend: PreviewBackend, outputs: Map<File, File>) {
    val settings = owner.settings
    DeferredImageComparer {
      ImageComparers.create(settings.comparer, File(owner.output, "compare"), settings.compares, notice = { logger.lifecycle(it) })
    }.use { comparer ->
      val runner = MedusaRunner(MedusaConfig(
        backend, modules.flatMap { it.sources }.distinct().map(::File), File(owner.output),
        comparer = comparer, filter = PreviewFilter(previewFilter.getOrElse(emptyList())),
        renderParallelism = settings.renders, compareParallelism = settings.compares,
        accessibility = settings.accessibility, failOnNoMatch = false,
        outputBySourceRoot = outputs, log = { logger.info(it) }, notice = { logger.lifecycle(it) },
      ))
      when (operation.get()) {
        "List" -> {
          val discovered = runner.discover()
          discovered.snapshots.forEach { logger.lifecycle("${it.preview.methodFqn}: ${it.goldenFile(0)}") }
          if (discovered.unlocated.isNotEmpty()) throw GradleException("Medusa: sources not found for ${discovered.unlocated}")
        }
        "Delete" -> runner.delete().forEach { logger.lifecycle("Deleted $it") }
        "Record", "CleanRecord" -> {
          val summary = if (operation.get() == "Record") runner.record() else runner.cleanRecord()
          summary.recorded.forEach { logger.lifecycle("${it.status}: ${it.golden}") }
          if (!summary.ok) throw GradleException(summary.errors.joinToString("\n"))
        }
        "Verify" -> {
          val summary = runner.verify()
          summary.orphans.forEach { logger.warn("Unused golden image: $it") }
          if (!summary.ok) throw GradleException("Medusa: ${summary.failures.size} of ${summary.results.size} preview(s) failed:\n" +
            summary.failures.joinToString("\n") { "${it.name}: ${it.outcome}" } + "\nReports: " + modules.joinToString { File(it.output, "report/index.html").toString() })
          logger.lifecycle("Medusa: ${summary.results.size} preview(s) match their golden images")
        }
        else -> error("Unknown Medusa operation")
      }
    }
  }
}

abstract class MedusaRootTask : DefaultTask() {
  init {
    // Like Gradle's built-in help tasks: this root dispatcher already includes its subprojects.
    impliesSubProjects = true
  }
  @get:Internal
  @get:Option(option = "preview", description = "Preview name or glob, repeatable")
  abstract val previewFilter: ListProperty<String>
}
