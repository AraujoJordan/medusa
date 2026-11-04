package dev.medusa.gradle

/** Pure scheduling decision. Dependency closures must come from the selected variant, not all configurations. */
internal object AggregationPlan {
  data class Module(
    val path: String,
    val application: Boolean = false,
    val dependencies: Set<String> = emptySet(),
    val automatic: Boolean = true,
    val manualAggregator: Boolean = false,
    val supported: Boolean = true,
    val settings: Any = Unit,
    val verifyOnCheck: Boolean = true,
  )

  data class Group(val host: String, val members: List<String>)
  data class Result(val groups: List<Group>, val standaloneReasons: Map<String, String>)

  fun create(modules: List<Module>, forCheck: Boolean = false): Result {
    require(modules.map { it.path }.distinct().size == modules.size) { "Duplicate module paths" }
    val byPath = modules.associateBy { it.path }
    fun closure(module: Module): Set<String> {
      val visited = mutableSetOf<String>()
      fun visit(path: String) {
        if (visited.add(path)) byPath[path]?.dependencies?.forEach(::visit)
      }
      module.dependencies.forEach(::visit)
      visited.remove(module.path)
      return visited
    }
    // Include opted-out and unrequested applications in ownership: they still make a library shared.
    val apps = modules.filter { it.application }.associateWith(::closure)
    val active = modules.filter { !forCheck || it.verifyOnCheck }.associateBy { it.path }
    val reasons = sortedMapOf<String, String>()
    val groups = sortedMapOf<String, MutableList<String>>()
    for (module in active.values.sortedBy { it.path }) {
      var host = module.path
      val reason = when {
        !module.automatic -> "automatic aggregation disabled"
        module.manualAggregator -> "explicit includeDependentModules configuration"
        !module.supported -> "unsupported variant or ambiguous source ownership"
        module.application -> null
        else -> {
          val owners = apps.filterValues { module.path in it }.keys.toList()
          when {
            owners.isEmpty() -> "no application host"
            owners.size > 1 -> "shared by multiple applications"
            else -> {
              val app = owners.single()
              when {
                app.path !in active -> "application is not participating"
                !app.automatic || app.manualAggregator || !app.supported -> "application is not eligible"
                app.settings != module.settings -> "settings differ from application"
                else -> { host = app.path; null }
              }
            }
          }
        }
      }
      if (reason != null) reasons[module.path] = reason
      groups.getOrPut(host) { mutableListOf() }.add(module.path)
    }
    return Result(groups.map { (host, members) -> Group(host, members.sorted()) }, reasons)
  }
}
