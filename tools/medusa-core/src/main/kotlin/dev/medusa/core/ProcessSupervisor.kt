package dev.medusa.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounded blocking process operations. Readers are interrupted by terminating their owned process. */
internal object ProcessSupervisor {
  private val timers = Executors.newScheduledThreadPool(1) { r -> Thread(r, "medusa-process-deadline").apply { isDaemon = true } }
  private val protectedRenderers = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Process, Boolean>())
  fun watch(process: Process, seconds: Long, sharedRenderer: Boolean = false): AutoCloseable {
    protectedRenderers[process] = sharedRenderer
    val timeout = timers.schedule({ stop(process) }, seconds, TimeUnit.SECONDS)
    return AutoCloseable { timeout.cancel(false); protectedRenderers.remove(process) }
  }
  fun stderr(process: Process): () -> String {
    val tail = StringBuilder()
    workers.submit {
      runCatching {
        process.errorStream.reader().use { reader ->
          val buffer = CharArray(2048)
          while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            synchronized(tail) { tail.append(buffer, 0, count); if (tail.length > 32768) tail.delete(0, tail.length - 32768) }
          }
        }
      }
    }
    return { synchronized(tail) { DiagnosticText.redact(tail.toString()) } }
  }
  private val workers = Executors.newCachedThreadPool { r -> Thread(r, "medusa-process-io").apply { isDaemon = true } }
  fun stop(process: Process) {
    process.toHandle().descendants().use { children -> children.forEach {
      val renderer = it.info().command().orElse("").replace('\\', '/').endsWith("/preview/renderer")
      if (protectedRenderers[process] != true || !renderer) it.destroyForcibly()
    } }
    process.destroyForcibly()
  }
  fun <T> bounded(process: Process, seconds: Long, operation: () -> T): T {
    require(seconds > 0) { "Process timeout must be positive" }
    val future = workers.submit<T> { operation() }
    try {
      return future.get(seconds, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
      stop(process)
      throw MedusaException("Process did not finish within $seconds seconds", e)
    } catch (e: InterruptedException) {
      stop(process)
      Thread.currentThread().interrupt()
      throw e
    } catch (e: java.util.concurrent.ExecutionException) {
      stop(process)
      throw e.cause ?: e
    } finally {
      future.cancel(true)
    }
  }
}
