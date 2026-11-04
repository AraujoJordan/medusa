package dev.medusa.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How many modules should start the Android CLI at once (`medusa.maxConcurrentRenders`) on this machine.
 *
 * Every Medusa task starts a CLI process that runs its own Gradle build. Those builds compete for CPU and memory, so more
 * is not faster: on a 10 core machine with 16 modules, 3 to 4 at once was fastest (41 s), 1 took 88 s, and 10 took 56 s
 * and 9 GB (`benchmark/CONCURRENCY.md`). The answer is the smaller of what the CPU and the memory can carry:
 *
 * ```
 * byCpu    = round(logicalCores / LOGICAL_CORES_PER_BUILD)
 * byMemory = floor((memory * MEMORY_FRACTION - BASE_MEMORY_MB) / perBuildMemoryMb)
 * cap      = clamp(min(byCpu, byMemory), 1, MAX)
 * ```
 *
 * The inputs are what the JVM reports, and on Linux the JVM reports the container's limits (cgroups), not the host's, so the
 * same formula follows a CI runner's `--cpus` and `--memory`.
 */
object ConcurrencyDefaults {
  /** One nested build keeps about this many logical cores busy while it works (measured: 37% of 10 cores with one at a time). */
  const val LOGICAL_CORES_PER_BUILD = 3.0

  /** Memory that is not available to builds: the outer Gradle daemon and the operating system. */
  const val BASE_MEMORY_MB = 1500L

  /** Share of the machine's (or container's) memory builds may use. */
  const val MEMORY_FRACTION = 0.6

  /** More than this never helped, and each extra build costs about 1 GB. */
  const val MAX = 8

  /** Gradle's own default daemon heap, for projects that do not set `org.gradle.jvmargs`. */
  const val GRADLE_DEFAULT_HEAP_MB = 512

  /**
   * Memory one concurrent build needs: its Gradle daemon (half of the heap limit is a typical working set, plus about
   * 400 MB of metaspace and native memory), a Gradle client JVM (about 135 MB) and a helper process (about 60 MB).
   * At least 900 MB, which is what was measured per extra build on a small project.
   */
  fun perBuildMemoryMb(nestedHeapMb: Int?): Long = max(900L, (nestedHeapMb ?: GRADLE_DEFAULT_HEAP_MB) / 2L + 400L + 200L)

  fun maxConcurrentBuilds(logicalCores: Int, totalMemoryMb: Long?, nestedHeapMb: Int?): Int {
    val byCpu = (logicalCores / LOGICAL_CORES_PER_BUILD).roundToInt()
    val byMemory = totalMemoryMb?.let { floor((it * MEMORY_FRACTION - BASE_MEMORY_MB) / perBuildMemoryMb(nestedHeapMb)).toInt() } ?: MAX
    return min(byCpu, byMemory).coerceIn(1, MAX)
  }

  /** One line for the build log, so a surprising value on a CI machine can be explained. */
  fun explain(logicalCores: Int, totalMemoryMb: Long?, nestedHeapMb: Int?): String {
    val cap = maxConcurrentBuilds(logicalCores, totalMemoryMb, nestedHeapMb)
    val memory = totalMemoryMb?.let { "%.1f GB".format(it / 1024.0) } ?: "unknown memory"
    return "$cap modules start the Android CLI at once ($logicalCores logical cores, $memory, " +
      "nested builds with a ${nestedHeapMb ?: GRADLE_DEFAULT_HEAP_MB} MB heap; set medusa.maxConcurrentRenders to change)"
  }

  private val XMX = Regex("""-Xmx(\d+)([kKmMgG]?)""")

  /** The `-Xmx` in a `org.gradle.jvmargs` value, in MB, or null if there is none. */
  fun parseHeapMb(jvmArgs: String?): Int? {
    val m = XMX.find(jvmArgs ?: return null) ?: return null
    val n = m.groupValues[1].toLong()
    val mb = when (m.groupValues[2].lowercase()) {
      "g" -> n * 1024
      "k" -> n / 1024
      else -> n // "m", or bytes written without a suffix, which nobody does for a heap
    }
    return mb.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  }
}
