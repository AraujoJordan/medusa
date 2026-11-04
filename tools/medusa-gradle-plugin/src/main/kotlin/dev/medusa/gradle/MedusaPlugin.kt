package dev.medusa.gradle

import dev.medusa.core.ConcurrencyDefaults
import dev.medusa.core.DEFAULT_MAX_PERCENT_DIFFERENCE
import dev.medusa.core.compare.ComparerSpec
import dev.medusa.core.compare.MagickOptions
import dev.medusa.core.compare.OdiffOptions
import dev.medusa.core.compare.YiqOptions
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

class MedusaPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create("medusa", MedusaExtension::class.java)
    extension.maxPercentDifference.convention(
      project.providers.gradleProperty("medusa.maxPercentDifference").map(String::toDouble).orElse(DEFAULT_MAX_PERCENT_DIFFERENCE),
    )
    extension.differ.convention(project.providers.gradleProperty("medusa.differ").orElse("odiff"))
    extension.autoInstall.convention(project.providers.gradleProperty("medusa.autoInstall").map(String::toBoolean).orElse(true))
    extension.fallbackToYiq.convention(project.providers.gradleProperty("medusa.fallbackToYiq").map(String::toBoolean).orElse(true))
    extension.installDir.convention(project.providers.gradleProperty("medusa.installDir"))
    extension.renderParallelism.convention(project.providers.gradleProperty("medusa.renderParallelism").map(String::toInt).orElse(1))
    extension.compareParallelism.convention(
      project.providers.gradleProperty("medusa.compareParallelism").map(String::toInt)
        .orElse(Runtime.getRuntime().availableProcessors()),
    )
    extension.accessibilitySnapshots.convention(
      project.providers.gradleProperty("medusa.accessibilitySnapshots").map(String::toBoolean).orElse(false),
    )
    fun prop(name: String) = project.providers.gradleProperty("medusa.$name")
    extension.yiq.threshold.convention(prop("yiq.threshold").map(String::toDouble).orElse(0.1))
    extension.yiq.antialiasing.convention(prop("yiq.antialiasing").map(String::toBoolean).orElse(false))
    extension.odiff.threshold.convention(prop("odiff.threshold").map(String::toDouble).orElse(0.1))
    extension.odiff.antialiasing.convention(prop("odiff.antialiasing").map(String::toBoolean).orElse(false))
    extension.odiff.useServer.convention(prop("odiff.useServer").map(String::toBoolean).orElse(true))
    extension.odiff.binary.convention(prop("odiff.binary"))
    extension.odiff.ignoreRegions.convention(prop("odiff.ignoreRegions").map { it.split(',').filter(String::isNotBlank) }.orElse(emptyList()))
    extension.magick.binary.convention(prop("magick.binary"))
    extension.magick.metric.convention(prop("magick.metric").orElse("AE"))
    extension.magick.fuzz.convention(prop("magick.fuzz").orElse("2%"))
    extension.magick.minPsnr.convention(prop("magick.minPsnr").map(String::toDouble).orElse(40.0))
    extension.includeDependentModules.convention(
      project.providers.gradleProperty("medusa.includeDependentModules").map(String::toBoolean).orElse(false),
    )
    extension.autoAggregate.convention(project.providers.gradleProperty("medusa.autoAggregate").map(String::toBoolean).orElse(true))
    AutoAggregation.install(project)
    extension.useDaemon.convention(project.providers.gradleProperty("medusa.useDaemon").map(String::toBoolean).orElse(true))
    extension.discoveryTimeoutSeconds.convention(prop("discoveryTimeoutSeconds").map(String::toLong).orElse(600L))
    extension.renderTimeoutSeconds.convention(prop("renderTimeoutSeconds").map(String::toLong).orElse(1800L))
    extension.comparisonTimeoutSeconds.convention(prop("comparisonTimeoutSeconds").map(String::toLong).orElse(60L))
    extension.visualRetryCount.convention(prop("visualRetryCount").map(String::toInt).orElse(0))
    extension.allowRecoveredPass.convention(prop("allowRecoveredPass").map(String::toBoolean).orElse(false))
    extension.verifyOnCheck.convention(true)
    extension.sourceDirs.from(
      listOf("main", "debug").flatMap { variant ->
        listOf("java", "kotlin").map { project.layout.projectDirectory.dir("src/$variant/$it") }
      },
    )

    extension.sourceDirs.from(
      project.provider {
        // Previews of dependent modules are found in those modules' sources; their goldens go next to them.
        if (extension.includeDependentModules.get()) {
          project.rootProject.allprojects.filter { it != project }.flatMap { other ->
            listOf("main", "debug").flatMap { variant ->
              listOf("java", "kotlin").map { other.layout.projectDirectory.dir("src/$variant/$it").asFile }
            }
          }
        } else {
          emptyList()
        }
      },
    )

    var registered = false
    for (id in listOf("com.android.application", "com.android.library")) {
      project.pluginManager.withPlugin(id) {
        if (registered) return@withPlugin
        registered = true
        register(project, extension)
      }
    }
  }

  private fun register(project: Project, extension: MedusaExtension) {
    // Nested builds read the project's gradle.properties, so its heap limit is what they will use.
    val heapMb = ConcurrencyDefaults.parseHeapMb(project.providers.gradleProperty("org.gradle.jvmargs").orNull)
    val cores = HostResources.logicalCores()
    val memoryMb = HostResources.totalMemoryMb()
    val configured = project.providers.gradleProperty("medusa.maxConcurrentRenders").map(String::toInt).orNull
    val maxConcurrent = configured ?: ConcurrencyDefaults.maxConcurrentBuilds(cores, memoryMb, heapMb)
    project.logger.info(
      "Medusa: " + if (configured != null) {
        "$configured modules start the Android CLI at once (medusa.maxConcurrentRenders)"
      } else {
        ConcurrencyDefaults.explain(cores, memoryMb, heapMb)
      },
    )
    val concurrency = project.gradle.sharedServices.registerIfAbsent("medusa", MedusaBuildService::class.java) { spec ->
      spec.maxParallelUsages.set(maxConcurrent)
    }
    // `./gradlew medusaVerify --preview=X` runs in every module and most do not have X. When the task was requested
    // without a project path, a module without a match is skipped; `:app:medusaVerify --preview=X` still fails.
    val requestedForAllModules = project.gradle.startParameter.taskNames.any { it.startsWith("medusa") }

    fun <T : MedusaTask> task(name: String, type: Class<T>): TaskProvider<T> =
      project.tasks.register(name, type) { t ->
        t.usesService(concurrency)
        t.rootDirectory.set(project.rootProject.layout.projectDirectory)
        // A Gradle path (":feature:home"): the CLI resolves directories ("feature/home") only for top-level projects.
        t.moduleRef.set(project.path.takeIf { it != ":" } ?: ".")
        t.skipWhenNoPreviewMatches.set(requestedForAllModules)
        t.sourceRoots.from(extension.sourceDirs)
        t.medusaOutputDir.set(project.layout.buildDirectory.dir("medusa"))
        t.comparerSpec.set(
          project.provider {
            ComparerSpec(
              differ = extension.differ.get(),
              maxPercentDifference = extension.maxPercentDifference.get(),
              yiq = YiqOptions(extension.yiq.threshold.get(), extension.yiq.antialiasing.get()),
              odiff = OdiffOptions(
                binary = extension.odiff.binary.orNull,
                threshold = extension.odiff.threshold.get(),
                antialiasing = extension.odiff.antialiasing.get(),
                ignoreRegions = extension.odiff.ignoreRegions.get(),
                useServer = extension.odiff.useServer.get(),
              ),
              magick = MagickOptions(
                binary = extension.magick.binary.orNull,
                metric = extension.magick.metric.get(),
                fuzz = extension.magick.fuzz.orNull?.takeIf { it.isNotBlank() },
                minPsnr = extension.magick.minPsnr.get(),
              ),
              autoInstall = extension.autoInstall.get(),
              fallbackToYiq = extension.fallbackToYiq.get(),
              installDir = extension.installDir.orNull,
              comparisonTimeoutSeconds = extension.comparisonTimeoutSeconds.get(),
            )
          },
        )
        t.reliability.set(project.provider { dev.medusa.core.ReliabilityOptions(
          extension.discoveryTimeoutSeconds.get(), extension.renderTimeoutSeconds.get(), extension.comparisonTimeoutSeconds.get(),
          extension.visualRetryCount.get(), extension.allowRecoveredPass.get()) })
        t.androidCliPath.set(extension.androidCliPath)
        t.includeDependentModules.set(extension.includeDependentModules)
        t.useDaemon.set(extension.useDaemon)
        t.renderParallelism.set(extension.renderParallelism)
        t.compareParallelism.set(extension.compareParallelism)
        t.accessibilitySnapshots.set(extension.accessibilitySnapshots)
      }

    // The AGP types are only loaded when AGP is really there; anything else falls back to src/main and src/debug.
    try {
      AgpSources.addTo(project, extension)
    } catch (e: LinkageError) {
      project.logger.info("Medusa: could not read the AGP source sets ($e); searching src/main and src/debug only")
    }

    project.tasks.register("medusaSetup", MedusaSetupTask::class.java) { t ->
      t.androidCliPath.set(extension.androidCliPath)
      t.concurrency.set(
        if (configured != null) "$configured (medusa.maxConcurrentRenders)" else ConcurrencyDefaults.explain(cores, memoryMb, heapMb),
      )
      t.comparerSpec.set(
        project.provider {
          ComparerSpec(
            differ = extension.differ.get(),
            odiff = OdiffOptions(binary = extension.odiff.binary.orNull),
            magick = MagickOptions(binary = extension.magick.binary.orNull),
            autoInstall = true,
            fallbackToYiq = false,
            installDir = extension.installDir.orNull,
              comparisonTimeoutSeconds = extension.comparisonTimeoutSeconds.get(),
          )
        },
      )
    }

    task("medusaDiagnose", MedusaDiagnoseTask::class.java)
    task("medusaList", MedusaListTask::class.java)
    task("medusaRecord", MedusaRecordTask::class.java)
    task("medusaCleanRecord", MedusaCleanRecordTask::class.java)
    task("medusaDelete", MedusaDeleteTask::class.java)
    val verify = task("medusaVerify", MedusaVerifyTask::class.java)


  }
}
