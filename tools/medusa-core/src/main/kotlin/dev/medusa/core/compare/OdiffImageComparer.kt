package dev.medusa.core.compare

import dev.medusa.core.Comparison
import dev.medusa.core.MedusaException
import dev.medusa.core.setup.OdiffInstaller
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Compares with the external [odiff](https://github.com/dmtrKovalenko/odiff) binary.
 *
 * By default it keeps up to [parallelism] long-running `odiff --server` processes and sends them one JSON line per image
 * (about 1 ms per comparison); with [useServer] = false it starts one process per comparison (about 6 ms).
 * The pass rule is `differing pixels / (width * height)` in percent against [maxPercentDifference]. odiff prints that
 * percentage rounded to two decimals, so it is computed here from the exact pixel count.
 */
class OdiffImageComparer(
  private val binary: File,
  private val threshold: Double,
  private val antialiasing: Boolean,
  ignoreRegions: List<String>,
  private val maxPercentDifference: Double,
  private val workDir: File,
  private val parallelism: Int = Runtime.getRuntime().availableProcessors(),
  private val useServer: Boolean = true,
) : ImageComparer {
  private val regions = ignoreRegions.map(Region::parse)
  private val counter = AtomicLong()
  private val idle = LinkedBlockingQueue<Server>()
  private val all = java.util.Collections.synchronizedList(mutableListOf<Server>())
  private val lock = Any()

  init {
    require(threshold in 0.0..1.0) { "odiff threshold must be between 0 and 1, was $threshold" }
    workDir.mkdirs()
  }

  override fun compare(golden: File, actual: File): Comparison {
    DeltaImage.sizeChange(golden, actual)?.let { return it }
    val (width, height) = PngHeader.size(golden)
    val diffFile = File(workDir, "odiff-${counter.incrementAndGet()}.png")
    try {
      val result = if (useServer) viaServer(golden, actual, diffFile) else oneShot(golden, actual, diffFile)
      if (result.layout) {
        return DeltaImage.failure(100f, "odiff reported a layout difference", golden, actual, null)
      }
      val percent = result.count * 100.0 / (width.toLong() * height)
      if (result.count == 0L || percent <= maxPercentDifference) return Comparison.Passed(percent.toFloat())
      return DeltaImage.failure(
        percent.toFloat(),
        "odiff: ${result.count} of ${width.toLong() * height} pixels differ (%.3f%%, allowed %.3f%%)".format(percent, maxPercentDifference),
        golden,
        actual,
        diffFile,
      )
    } finally {
      diffFile.delete()
    }
  }

  override fun close() {
    synchronized(lock) { all.forEach { it.stop() }; all.clear(); idle.clear() }
  }

  private class Result(val count: Long, val layout: Boolean = false)

  // ------------------------------------------------------------------ one process per comparison

  private fun oneShot(golden: File, actual: File, diff: File): Result {
    val cmd = buildList {
      add(binary.path); add(golden.path); add(actual.path); add(diff.path)
      add("--parsable-stdout"); add("--fail-on-layout"); add("-t"); add(threshold.toString())
      if (antialiasing) add("--aa")
      if (regions.isNotEmpty()) { add("-i"); add(regions.joinToString(",")) }
    }
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw MedusaException("odiff did not finish within 60 s for ${golden.name}")
    }
    // Exit codes: 0 match, 21 layout, 22 pixel difference. Anything else is an error, whose text is the output.
    return when {
      output == "layout" -> Result(0, layout = true)
      output == "0" -> Result(0)
      Regex("""\d+;[\d.]+.*""").matches(output.lineSequence().first()) -> Result(output.substringBefore(';').toLong())
      else -> throw MedusaException("odiff failed on ${golden.name}: $output")
    }
  }

  // ------------------------------------------------------------------ server pool

  private fun viaServer(golden: File, actual: File, diff: File): Result {
    var lastError: Exception? = null
    repeat(2) {
      val server = borrow()
      try {
        val result = server.request(golden, actual, diff)
        idle.put(server)
        return result
      } catch (e: java.io.IOException) {
        // The server died or answered garbage: drop it and try once more with a fresh one.
        server.stop()
        synchronized(lock) { all.remove(server) }
        lastError = e
      }
    }
    throw MedusaException("odiff --server failed twice for ${golden.name}: ${lastError?.message}", lastError)
  }

  private fun borrow(): Server {
    idle.poll()?.let { return it }
    synchronized(lock) {
      if (all.size < maxOf(1, parallelism)) return Server().also { all += it }
    }
    return idle.take()
  }

  private inner class Server {
    private val process: Process = ProcessBuilder(binary.path, "--server")
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    private val writer: BufferedWriter = process.outputStream.bufferedWriter()
    private val reader: BufferedReader = process.inputStream.bufferedReader()
    private var next = 0

    init {
      val ready = reader.readLine() ?: throw java.io.IOException("odiff --server exited before it was ready")
      if ("\"ready\"" !in ready) throw java.io.IOException("unexpected odiff --server greeting: $ready")
    }

    fun request(golden: File, actual: File, diff: File): Result {
      val id = next++
      val request = buildJsonObject {
        put("requestId", id)
        put("base", golden.path)
        put("compare", actual.path)
        put("output", diff.path) // mandatory, even when the images match
        put(
          "options",
          buildJsonObject {
            put("threshold", threshold)
            put("antialiasing", antialiasing)
            put("failOnLayoutDiff", true)
            if (regions.isNotEmpty()) {
              put(
                "ignoreRegions",
                JsonArray(
                  regions.map {
                    buildJsonObject { put("x1", it.x1); put("y1", it.y1); put("x2", it.x2); put("y2", it.y2) }
                  },
                ),
              )
            }
          },
        )
      }
      writer.write(Json.encodeToString(JsonObject.serializer(), request))
      writer.newLine()
      writer.flush()
      val line = reader.readLine() ?: throw java.io.IOException("odiff --server closed its output")
      val response = try {
        Json.parseToJsonElement(line).jsonObject
      } catch (e: Exception) {
        throw java.io.IOException("odiff --server sent something that is not JSON: $line", e)
      }
      if (response["requestId"]?.jsonPrimitive?.longOrNull != id.toLong()) {
        throw java.io.IOException("odiff --server answered another request: $line")
      }
      response["error"]?.let { throw MedusaException("odiff failed on ${golden.name}: ${(it as? JsonPrimitive)?.contentOrNull ?: it}") }
      return when {
        response["match"]?.jsonPrimitive?.booleanOrNull == true -> Result(0)
        response["reason"]?.jsonPrimitive?.contentOrNull == "layout-diff" -> Result(0, layout = true)
        else -> Result(response["diffCount"]?.jsonPrimitive?.longOrNull ?: throw java.io.IOException("no diffCount in: $line"))
      }
    }

    fun stop() {
      runCatching { writer.close() }
      process.destroy()
      if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
  }

  companion object {
    /** Finds `odiff`: the configured path, PATH, `~/.medusa/bin` (where Medusa installs it) and the usual Homebrew directories. */
    fun locate(
      explicit: String? = null,
      path: String? = System.getenv("PATH"),
      installDir: File = OdiffInstaller.defaultInstallDir(),
      systemDirs: List<String> = BinaryLocator.DEFAULT_SYSTEM_DIRS,
    ): File = BinaryLocator.locate("odiff", explicit, path, "./gradlew medusaSetup  (or: brew install odiff)", listOf(installDir), systemDirs)
  }
}
