package dev.medusa.gradle

import java.lang.management.ManagementFactory

/**
 * What this machine, or this container, can give to builds. Both values come from the JVM, which on Linux reads the
 * container's cgroup limits (`--cpus`, `--memory`), so CI runners are measured by their allowance, not by their host.
 */
internal object HostResources {
  fun logicalCores(): Int = Runtime.getRuntime().availableProcessors()

  /** Total physical (or container) memory in MB, or null if the JVM does not say. */
  fun totalMemoryMb(): Long? = try {
    (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize?.div(1024 * 1024)
  } catch (e: Throwable) {
    null
  }
}
