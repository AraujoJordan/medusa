package dev.medusa.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

internal object AutoAggregation {
  fun install(project: Project) {
    // The CLI's nested preparation build cannot execute Medusa tasks. Avoid resolving every
    // module's runtime graph a second time inside that build.
    val requested = project.gradle.startParameter.taskNames
    if (requested.isNotEmpty() && requested.all {
        val name = it.substringAfterLast(':')
        name.startsWith("generate") && name.endsWith("ComposePreviewRunfiles")
      }) return
    val root = project.rootProject
    val marker = "dev.medusa.aggregation.installed"
    if (root.extensions.extraProperties.has(marker)) return
    root.extensions.extraProperties.set(marker, true)
    val graphs = mutableMapOf<String, Set<String>?>()
    root.allprojects.forEach { candidate ->
      var observed = false
      listOf("com.android.application", "com.android.library").forEach { plugin ->
        candidate.pluginManager.withPlugin(plugin) {
          if (!observed && !candidate.state.executed) {
            observed = true
            candidate.afterEvaluate { p ->
              val configuration = p.configurations.findByName("debugRuntimeClasspath")
              graphs[p.path] = try {
                val result = configuration?.incoming?.resolutionResult
                check(result?.allDependencies?.none { it is org.gradle.api.artifacts.result.UnresolvedDependencyResult } != false) {
                  "unresolved debug dependencies"
                }
                result?.allComponents?.mapNotNull { component ->
                  val id = component.id as? ProjectComponentIdentifier
                  id?.takeIf { it.build.buildPath == ":" && it.projectPath != p.path }?.projectPath
                }?.toSet()
              } catch (e: Exception) {
                root.logger.info("Medusa: cannot resolve ${p.path} debug dependencies: ${e.message}")
                null
              }
            }
          }
        }
      }
    }
    project.gradle.projectsEvaluated {
      val androidProjects = root.allprojects.filter {
        it.plugins.hasPlugin("com.android.application") || it.plugins.hasPlugin("com.android.library")
      }
      val participating = androidProjects.filter { it.extensions.findByType(MedusaExtension::class.java) != null }
      val configs = participating.associate { p ->
        val task = p.tasks.named("medusaVerify", MedusaTask::class.java).get()
        p.path to GroupModule(p.path, task.sourceRoots.files.map { it.absolutePath }, task.medusaOutputDir.get().asFile.absolutePath,
          ModuleSettings(task.androidCliPath.orNull, task.comparerSpec.get(), task.useDaemon.get(),
            task.renderParallelism.get(), task.compareParallelism.get(), task.accessibilitySnapshots.get(), task.reliability.get()), task.includeDependentModules.get())
      }
      var graphKnown = true
      val nodes = androidProjects.map { p ->
        val extension = p.extensions.findByType(MedusaExtension::class.java)
        val configuration = p.configurations.findByName("debugRuntimeClasspath")
        val dependencies = graphs[p.path]
        if (dependencies == null) graphKnown = false
        AggregationPlan.Module(p.path, p.plugins.hasPlugin("com.android.application"), dependencies.orEmpty(),
          automatic = extension?.autoAggregate?.get() ?: false,
          manualAggregator = extension?.includeDependentModules?.get() ?: false,
          supported = configuration != null, settings = configs[p.path]?.settings ?: p.path,
          verifyOnCheck = extension?.verifyOnCheck?.get() ?: false)
      }
      // An unknown app graph might hide another owner. Conservatively disable grouping for the build.
      if (!graphKnown) root.logger.info("Medusa: incomplete debug dependency graph; automatic aggregation disabled")
      val safeNodes = if (graphKnown) nodes else nodes.map { it.copy(supported = false) }
      val service = root.gradle.sharedServices.registrations.findByName("medusa")?.service
      fun plan(check: Boolean): List<AggregationPlan.Group> {
        val result = AggregationPlan.create(safeNodes, check)
        result.standaloneReasons.forEach { (path, reason) -> root.logger.info("Medusa: $path standalone: $reason") }
        return result.groups.mapNotNull { group ->
          val members = group.members.filter { it in configs }
          if (members.isEmpty() || group.host !in configs) null else group.copy(members = members)
        }
      }
      val normalPlan = plan(false)
      val checkPlan = plan(true)
      val operations = listOf("List", "Record", "Verify", "Delete", "CleanRecord", "Diagnose")
      for (operation in operations) {
        val rootName = "medusa$operation"
        if (root.tasks.findByName(rootName) != null) continue
        val entry = root.tasks.register(rootName, MedusaRootTask::class.java) { it.group = "medusa" }
        for ((index, group) in normalPlan.withIndex()) {
          if (group.members.size == 1) {
            val standalone = root.project(group.host).tasks.named(rootName, MedusaTask::class.java)
            standalone.configure { it.previewFilter.set(entry.flatMap { task -> task.previewFilter }) }
            entry.configure { it.dependsOn(standalone) }
            continue
          }
          val task = root.tasks.register("medusaGrouped${operation}$index", MedusaAggregateTask::class.java) { t ->
            t.members.set(group.members.map { configs.getValue(it) })
            t.universe.set(configs.values.toList())
            t.workingDirectory.set(root.projectDir.absolutePath)
            t.host.set(group.host)
            t.operation.set(operation)
            t.previewFilter.set(entry.flatMap { it.previewFilter })
            if (service != null) t.usesService(service)
          }
          entry.configure { it.dependsOn(task) }
        }
      }
      for ((index, group) in checkPlan.withIndex()) {
        if (group.members.size == 1) {
          val p = root.project(group.host)
          p.tasks.matching { it.name == "check" }.configureEach { it.dependsOn(p.tasks.named("medusaVerify")) }
          continue
        }
        val task = root.tasks.register("medusaCheckGroup$index", MedusaAggregateTask::class.java) { t ->
          t.members.set(group.members.map { configs.getValue(it) })
          t.universe.set(configs.values.toList())
          t.workingDirectory.set(root.projectDir.absolutePath)
          t.host.set(group.host)
          t.operation.set("Verify")
          if (service != null) t.usesService(service)
        }
        group.members.forEach { member ->
          root.project(member).tasks.matching { it.name == "check" }.configureEach { it.dependsOn(task) }
        }
      }
    }
  }
}
