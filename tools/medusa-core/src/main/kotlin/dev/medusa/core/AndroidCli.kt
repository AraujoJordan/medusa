package dev.medusa.core

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Source of previews and rendered images. The real one shells out to the Android CLI; tests fake it. */
interface PreviewBackend {
  fun list(): ListResult
  fun withDaemon(enabled: Boolean): PreviewBackend = this
  val events: List<String> get() = emptyList()

  /**
   * Renders [fqns] into [outputDir] and emits every image **as soon as it is complete**, while the
   * renderer is still working on the others. A preview that failed to render is emitted with [RenderedPreview.error].
   * Throws [MedusaException] if the renderer fails as a whole. Cancelling the collector stops the renderer.
   */
  fun render(fqns: List<String>, outputDir: File): Flow<RenderedPreview>
}

class MedusaException(message: String, cause: Throwable? = null) : RuntimeException(DiagnosticText.redact(message), cause?.let { sanitizedCause(it) })

private fun sanitizedCause(failure: Throwable, depth: Int = 0): Throwable =
  RuntimeException("${failure.javaClass.name}: ${DiagnosticText.redact(failure.message.orEmpty())}",
    failure.cause?.takeIf { depth < 8 }?.let { sanitizedCause(it, depth + 1) }).apply { stackTrace = failure.stackTrace }

private val json = Json { ignoreUnknownKeys = true }

/**
 * The CLI prints progress lines ("Build log: ...") before the JSON document, so
 * take everything from the first line that is exactly an opening brace.
 */
internal fun extractJson(stdout: String): String {
  val lines = stdout.lines()
  val start = lines.indexOfFirst { it.trim() == "{" }
  if (start < 0) throw MedusaException("Android CLI printed no JSON. Output was:\n${DiagnosticText.redact(stdout).takeLast(32768)}")
  return lines.drop(start).joinToString("\n")
}

fun parseListResult(stdout: String): ListResult = json.decodeFromString(extractJson(stdout))

fun parseRenderResult(stdout: String): RenderResult = json.decodeFromString(extractJson(stdout))

/**
 * Runs `android preview ...` from [workingDir]. [moduleRef] is the CLI "reference",
 * a path relative to [workingDir] (for example `sample`); an empty reference makes the CLI
 * resolve the root project, which fails for multi-module builds.
 */
class AndroidCliBackend(
  private val workingDir: File,
  private val moduleRef: String,
  private val androidCli: File = AndroidCliLocator.locate(),
  private val includeDependentModules: Boolean = false,
  private val daemon: Boolean = false,
  private val componentTree: Boolean = false,
  private val buildArgs: List<String> = emptyList(),
  private val pollMillis: Long = 20,
  private val log: (String) -> Unit = {},
  private val reliability: ReliabilityOptions = ReliabilityOptions(),
  private val notice: (String) -> Unit = {},
) : PreviewBackend {
  private val recoveryEvents = java.util.concurrent.ConcurrentLinkedQueue<String>()
  override val events: List<String> get() = recoveryEvents.toList()

  override fun withDaemon(enabled: Boolean): PreviewBackend = AndroidCliBackend(workingDir, moduleRef, androidCli,
    includeDependentModules, enabled, componentTree, buildArgs, pollMillis, log, reliability, notice)

  override fun list(): ListResult {
    val args = buildList {
      add("preview")
      add("list")
      add("--format=json")
      if (includeDependentModules) add("--all")
      add(moduleRef)
      addBuildArgs()
    }
    val result = try { parseListResult(runBlocking(args)) } catch (failure: Exception) {
      throw MedusaException("Android CLI discovery failed: ${failure.message}", failure)
    }
    if (!result.success) {
      throw MedusaException("`android preview list` failed: ${result.globalError ?: "unknown error"}")
    }
    return result
  }

  override fun render(fqns: List<String>, outputDir: File): Flow<RenderedPreview> = channelFlow {
    require(fqns.isNotEmpty()) { "render needs at least one preview" }
    outputDir.mkdirs()
    val wanted = fqns.toSet()
    val emitted = ConcurrentHashMap.newKeySet<String>()
    val process = start(
      buildList {
        add("preview")
        add("render")
        add("--format=json")
        add("--output=${outputDir.absolutePath}")
        if (daemon) add("--daemon")
        if (componentTree) add("--component-tree")
        fqns.forEach { add("--fqn=$it") }
        add(moduleRef)
        addBuildArgs()
      },
    )
    val deadline = ProcessSupervisor.watch(process, reliability.renderTimeoutSeconds, daemon)
    val stderr = ProcessSupervisor.stderr(process)
    try {
      // The result list only arrives when the process ends, but the PNGs appear one by one before that.
      val stdout = async(Dispatchers.IO) {
        try {
          kotlinx.coroutines.runInterruptible { ProcessSupervisor.bounded(process, reliability.renderTimeoutSeconds) { process.inputStream.bufferedReader().readText() } }
        } catch (e: java.io.IOException) {
          // Cancelling (the collector stopped early) kills the process, which closes this stream under a blocked read.
          // That is expected then, and must not turn a clean cancellation into a failure.
          if (!isActive) "" else throw e
        }
      }
      val watcher = launch(Dispatchers.IO) {
        while (isActive) {
          sweep(outputDir, wanted, emitted) { send(it) }
          delay(pollMillis)
        }
      }
      val output = stdout.await()
      val exit = process.waitFor()
      watcher.cancelAndJoin()
      sweep(outputDir, wanted, emitted) { send(it) }

      // A non-zero exit still carries a JSON body with the reason, so only fail when there is none.
      if (exit != 0 && output.lines().none { it.trim() == "{" }) {
        throw MedusaException("`android preview render` exited with $exit:\n${DiagnosticText.redact(output).takeLast(32768)}")
      }
      val result = parseRenderResult(output)
      if (result.globalError != null || ((exit != 0 || !result.success) && result.results.none { it.error != null })) {
        throw MedusaException("`android preview render` failed: ${result.globalError ?: "unknown error"}")
      }
      val authoritative = result.results.mapNotNull { it.imagePath?.let { path -> File(path).canonicalPath } }.toSet()
      if (emitted.any { it !in authoritative }) throw MedusaException("CLI final manifest omitted an image that was streamed")
      if (result.results.any { it.methodFqn !in wanted }) throw MedusaException("CLI returned an unrequested preview")
      // The CLI daemon can retain a classloader that cannot see a newly added dependency class.
      // Retry only wholly failed class-not-found groups, once, in a fresh renderer. Successful
      // images and other errors are never retried or hidden.
      val retry = if (daemon) result.results.groupBy { it.methodFqn }.filterValues { entries ->
        entries.all { r ->
          r.imagePath == null && r.error?.status == "VALIDATION_ERROR" &&
            r.error.message?.contains("could not be found on the classpath") == true
        }
      }.keys else emptySet()
      for (r in result.results) {
        if (r.methodFqn !in retry && (r.error != null || r.imagePath == null || emitted.add(File(r.imagePath).canonicalPath))) send(r)
      }
      if (retry.isNotEmpty()) {
        val recovery = "Retrying ${retry.size} preview(s) with a fresh renderer after daemon classpath failure"
        recoveryEvents.add(recovery)
        notice(recovery)
        log("Medusa: retrying ${retry.size} preview(s) in a fresh renderer after daemon classpath validation failed")
        AndroidCliBackend(workingDir, moduleRef, androidCli, includeDependentModules,
          daemon = false, componentTree = componentTree, buildArgs = buildArgs, pollMillis = pollMillis, log = log, reliability = reliability, notice = notice)
          .render(retry.toList(), File(outputDir, "fresh-renderer")).collect { send(it) }
      }
    } catch (failure: Exception) {
      if (failure is kotlinx.coroutines.CancellationException) throw failure
      throw MedusaException("${DiagnosticText.redact(failure.message ?: "CLI failure")}\nCLI stderr: ${stderr()}", failure)
    } finally {
      if (process.isAlive) ProcessSupervisor.stop(process)
      deadline.close()
    }
  }

  /** Emits every complete, wanted PNG under [root] that was not emitted before. */
  private suspend fun sweep(root: File, wanted: Set<String>, emitted: MutableSet<String>, send: suspend (RenderedPreview) -> Unit) {
    wanted.map { it.substringBeforeLast('.').replace('.', File.separatorChar) }.distinct()
      .flatMap { File(root, it).listFiles().orEmpty().asList() }
      .filter { it.isFile && it.extension == "png" }.sortedBy { it.path }.forEach { file ->
      val fqn = fqnOf(root, file) ?: return@forEach
      if (fqn.first !in wanted) return@forEach
      if (file.canonicalPath in emitted || !isCompletePng(file)) return@forEach
      // With --component-tree the sibling .json must be complete too, so consumers never see half a result.
      val tree = File(file.path.removeSuffix(".png") + ".json")
      if (componentTree && !isCompleteJson(tree)) return@forEach
      emitted += file.canonicalPath
      send(
        RenderedPreview(
          previewId = fqn.first.substringAfterLast('.'),
          methodFqn = fqn.first,
          imagePath = file.absolutePath,
          componentTreePath = tree.takeIf { componentTree }?.absolutePath,
        ),
      )
    }
  }

  private fun MutableList<String>.addBuildArgs() {
    if (buildArgs.isNotEmpty()) {
      add("--")
      addAll(buildArgs)
    }
  }

  private fun start(args: List<String>): Process {
    val command = listOf(androidCli.absolutePath, "--no-metrics") + args
    log(DiagnosticText.redact("$ ${command.joinToString(" ")}"))
    return ProcessBuilder(command)
      .directory(workingDir)
      .start()
  }

  private fun runBlocking(args: List<String>): String {
    val process = start(args)
    val stderr = ProcessSupervisor.stderr(process)
    ProcessSupervisor.watch(process, reliability.discoveryTimeoutSeconds).use {
      try {
        return ProcessSupervisor.bounded(process, reliability.discoveryTimeoutSeconds) {
          val stdout = process.inputStream.bufferedReader().readText()
          val exit = process.waitFor()
          if (exit != 0) throw MedusaException("Android CLI exited with $exit: ${DiagnosticText.redact(stdout)}")
          stdout
        }
      } catch (failure: Exception) {
        throw MedusaException("${DiagnosticText.redact(failure.message ?: "CLI failure")}\nCLI stderr: ${stderr()}", failure)
      } finally { if (process.isAlive) ProcessSupervisor.stop(process) }
    }
  }

  internal companion object {
    private val IEND_TRAILER = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
    private val NAME = Regex("""(.+)_(\d+)\.png""")

    /** A PNG is complete once its closing IEND chunk is on disk. */
    fun isCompletePng(file: File): Boolean {
      val length = file.length()
      if (length < 8 + IEND_TRAILER.size) return false
      return try {
        RandomAccessFile(file, "r").use { raf ->
          raf.seek(length - IEND_TRAILER.size)
          val tail = ByteArray(IEND_TRAILER.size)
          raf.readFully(tail)
          tail.contentEquals(IEND_TRAILER)
        }
      } catch (_: java.io.IOException) {
        false
      }
    }

    /** The component tree is one pretty-printed JSON object, so a complete file ends with its closing brace. */
    fun isCompleteJson(file: File): Boolean {
      val length = file.length()
      if (length < 2) return false
      return try {
        RandomAccessFile(file, "r").use { raf ->
          val n = minOf(length, 16L).toInt()
          raf.seek(length - n)
          val tail = ByteArray(n)
          raf.readFully(tail)
          String(tail, Charsets.UTF_8).trimEnd().endsWith("}")
        }
      } catch (_: java.io.IOException) {
        false
      }
    }

    /** `<root>/dev/app/ButtonKt/PrimaryPreview_0.png` -> (`dev.app.ButtonKt.PrimaryPreview`, 0). */
    fun fqnOf(root: File, file: File): Pair<String, Int>? {
      val relative = file.relativeToOrNull(root) ?: return null
      val match = NAME.matchEntire(relative.name) ?: return null
      val packageAndClass = relative.parentFile?.path?.replace(File.separatorChar, '.') ?: return null
      return "$packageAndClass.${match.groupValues[1]}" to match.groupValues[2].toInt()
    }
  }
}

object AndroidCliLocator {
  fun locate(
    explicit: String? = null,
    path: String? = System.getenv("PATH"),
    home: String = System.getProperty("user.home"),
    systemDirs: List<String> = listOf("/usr/local/bin", "/opt/homebrew/bin"),
  ): File {
    // A configured path is a decision: do not quietly use a different binary if it is wrong.
    if (explicit != null) {
      return File(explicit).takeIf { it.isFile && it.canExecute() }
        ?: throw MedusaException("Android CLI not found at the configured androidCliPath: $explicit")
    }
    val candidates = buildList {
      path?.split(File.pathSeparatorChar).orEmpty().filter { it.isNotBlank() }.forEach { add(File(it, "android")) }
      add(File(home, ".local/bin/android"))
      systemDirs.forEach { add(File(it, "android")) }
    }
    return candidates.firstOrNull { it.isFile && it.canExecute() }
      ?: throw MedusaException(
        "Android CLI not found. Run `./gradlew medusaSetup` to install it, or install it from " +
          "https://developer.android.com/tools/agents, or set medusa { androidCliPath = \"...\" }. Looked in: ${candidates.joinToString()}",
      )
  }
}
