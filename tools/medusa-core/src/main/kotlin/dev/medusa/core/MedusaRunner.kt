package dev.medusa.core

import app.cash.paparazzi.Differ
import dev.medusa.core.compare.ImageComparer
import dev.medusa.core.compare.PaparazziImageComparer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class MedusaConfig(
  val backend: PreviewBackend,
  /** Directories scanned for the .kt file that declares each preview. */
  val sourceRoots: List<File>,
  /** `build/medusa`: rendered images, failures and reports go below it. */
  val outputDir: File,
  /** Share of pixels (0-100) allowed to differ. Paparazzi's default. */
  val maxPercentDifference: Double = DEFAULT_MAX_PERCENT_DIFFERENCE,
  val differ: Differ = Differs.byName(null),
  val filter: PreviewFilter = PreviewFilter(emptyList()),
  /** Concurrent `android preview render` processes. Each pays the CLI's fixed cost, so 1 is usually best. */
  val renderParallelism: Int = 1,
  /** Images compared at once while rendering continues. */
  val compareParallelism: Int = Runtime.getRuntime().availableProcessors(),
  /** Also snapshot the accessibility tree (`<golden>.semantics.txt`). The backend must render with the component tree. */
  val accessibility: Boolean = false,
  /**
   * A `--preview` filter that matches nothing is an error, unless this is false. Builds with several modules run the
   * same task in every module, and most modules will not contain the preview that was asked for.
   */
  val failOnNoMatch: Boolean = true,
  val log: (String) -> Unit = {},
  /** Visible by default (Gradle lifecycle): things the user should notice, such as a module that skipped a filter. */
  val notice: (String) -> Unit = {},
  /**
   * Decides whether an image matches its golden. When null, Paparazzi's [differ] is used with [maxPercentDifference].
   * The caller owns it and closes it ([ImageComparer] may hold helper processes).
   */
  val comparer: ImageComparer? = null,
  /** Aggregated runs keep reports and artifacts in each source module's output directory. */
  val outputBySourceRoot: Map<File, File> = emptyMap(),
)

/** Previews that matched the filter, and previews whose source file could not be found. */
data class Discovery(val snapshots: List<Snapshot>, val unlocated: List<PreviewInfo>)

enum class RecordStatus { NEW, CHANGED, UNCHANGED }

data class Recorded(val name: String, val golden: File, val status: RecordStatus)

data class RecordSummary(val recorded: List<Recorded>, val errors: List<String>) {
  val ok: Boolean get() = errors.isEmpty()
}

sealed interface Outcome {
  data object Passed : Outcome
  data class Missing(val golden: File) : Outcome

  /** [delta] is Paparazzi's expected | delta | actual image; [actual] is the new rendering on its own. */
  data class Failed(val message: String, val delta: File?, val actual: File?) : Outcome
}

data class VerifyResult(val name: String, val golden: File?, val outcome: Outcome)

data class VerifySummary(
  val results: List<VerifyResult>,
  val orphans: List<File>,
  val reportFile: File,
) {
  val failures: List<VerifyResult> get() = results.filter { it.outcome !is Outcome.Passed }
  val ok: Boolean get() = failures.isEmpty()
}

class MedusaRunner(private val config: MedusaConfig) {
  private val comparer: ImageComparer = config.comparer ?: PaparazziImageComparer(config.differ, config.maxPercentDifference)
  private val renderedDir = File(config.outputDir, "rendered")

  private fun outputFor(file: File): File = config.outputBySourceRoot.entries
    .filter { file.canonicalFile.toPath().startsWith(it.key.canonicalFile.toPath()) }
    .maxByOrNull { it.key.canonicalPath.length }?.value ?: config.outputDir

  fun discover(): Discovery {
    val started = System.nanoTime()
    val all = config.backend.list().previews
    val listed = System.nanoTime()
    // Golden names must not depend on the filter, so locate every preview and detect name collisions among all of
    // them first; only then apply `--preview`.
    val located = mutableListOf<Snapshot>()
    val unlocated = mutableListOf<PreviewInfo>()
    val sources = SourceLocator.Index(config.sourceRoots)
    for ((_, entries) in all.groupBy { it.methodFqn }) {
      val preview = entries.first()
      val source = sources.locate(preview)
      if (source == null) unlocated += preview else located += Snapshot(preview, source, entries.size)
    }
    val snapshots = qualifyCollisions(located).filter { config.filter.matches(it.preview) }
    val missing = unlocated.filter { config.filter.matches(it) }
    if (!config.filter.isEmpty && snapshots.isEmpty() && missing.isEmpty()) {
      val available = all.map { it.methodFqn }.distinct()
      if (!config.failOnNoMatch) {
        config.notice("Skipped: no preview in this module matches the --preview filter (${available.size} previews here)")
        return Discovery(emptyList(), emptyList())
      }
      throw MedusaException("No preview matches the --preview filter. Available: ${available.joinToString()}")
    }
    config.log("Medusa discovery: list=${(listed - started) / 1_000_000} ms, sources=${(System.nanoTime() - listed) / 1_000_000} ms")
    return Discovery(snapshots, missing)
  }

  /**
   * Two previews with the same function name in one file (different classes or objects) would share a golden name.
   * Only those get the class name added, so existing goldens keep their names.
   */
  private fun qualifyCollisions(snapshots: List<Snapshot>): List<Snapshot> {
    val colliding = snapshots.groupBy { it.sourceFile to it.preview.name }.filterValues { it.size > 1 }.keys
    if (colliding.isEmpty()) return snapshots
    return snapshots.map {
      if (it.sourceFile to it.preview.name in colliding) {
        it.copy(qualifier = it.preview.className.substringAfterLast('.').replace('$', '_'))
      } else {
        it
      }
    }
  }

  fun record(): RecordSummary = record(discover())

  private fun record(discovery: Discovery): RecordSummary {
    val errors = discovery.unlocated.map(::unlocatedMessage).toMutableList()
    val outcomes = pipeline(discovery.snapshots, ::recordOne)
    val recorded = outcomes.mapNotNull { it.second.first }
    outcomes.mapNotNull { it.second.second }.forEach { errors += it }
    removeStaleIndexedGoldens(outcomes.filter { it.second.second == null }.map { it.first })
    return RecordSummary(recorded, errors)
  }

  fun verify(): VerifySummary {
    val discovery = discover()
    (config.outputBySourceRoot.values + config.outputDir).distinct().forEach { File(it, "failures").deleteRecursively() }
    val results = mutableListOf<VerifyResult>()
    discovery.unlocated.forEach {
      results += VerifyResult(it.methodFqn, null, Outcome.Failed(unlocatedMessage(it), null, null))
    }
    results += pipeline(discovery.snapshots, ::verifyOne).map { it.second }
    // Orphans are only meaningful when every preview was considered.
    val expected = results.mapNotNull { it.golden }.flatMap { g ->
      listOfNotNull(g, File(g.path.removeSuffix(".png") + ".semantics.txt").takeIf { config.accessibility })
    }.map { it.canonicalFile }.toSet()
    val orphans = if (config.filter.isEmpty) findOrphans(expected) else emptyList()
    for (output in config.outputBySourceRoot.values.distinct()) {
      Report.write(output, results.filter { it.golden?.let(::outputFor) == output }, orphans.filter { outputFor(it) == output })
    }
    val report = if (config.outputBySourceRoot.isEmpty()) Report.write(config.outputDir, results, orphans)
      else File(config.outputDir, "report/index.html")
    return VerifySummary(results, orphans, report)
  }

  /** Deletes the goldens of the matching previews (every index). Returns the files removed. */
  fun cleanRecord(): RecordSummary {
    val discovery = discover()
    val deleted = discovery.snapshots.flatMap { it.existingGoldens() }.filter { it.delete() }
    config.notice("Deleted ${deleted.count { it.extension == "png" }} golden image(s)")
    return record(discovery)
  }

  fun delete(): List<File> =
    discover().snapshots.flatMap { it.existingGoldens() }.filter { it.delete() }

  private class Rendered(val snapshot: Snapshot, val index: Int, val image: File?, val tree: File?, val error: String?) {
    val name: String get() = snapshot.displayName(index)
    val golden: File get() = snapshot.goldenFile(index)
    val semanticsGolden: File get() = snapshot.semanticsFile(index)
  }

  private fun recordOne(r: Rendered): Pair<Recorded?, String?> {
    val image = r.image ?: return null to "${r.name}: ${r.error}"
    val golden = r.golden
    var status = when {
      !golden.exists() -> RecordStatus.NEW
      comparer.compare(golden, image) is Comparison.Passed -> RecordStatus.UNCHANGED
      else -> RecordStatus.CHANGED
    }
    if (status != RecordStatus.UNCHANGED) image.copyTo(golden, overwrite = true)
    if (config.accessibility) {
      val actual = AccessibilityTree.normalize(r.tree!!.readText())
      val semantics = r.semanticsGolden
      if (!semantics.exists() || semantics.readText() != actual) {
        semantics.writeText(actual)
        if (status == RecordStatus.UNCHANGED) status = RecordStatus.CHANGED
      }
    }
    return Recorded(r.name, golden, status) to null
  }

  private fun verifyOne(r: Rendered): VerifyResult {
    val golden = r.golden
    val image = r.image
    var outcome = when {
      image == null -> Outcome.Failed(r.error ?: "render failed", null, null)
      !golden.exists() -> Outcome.Missing(golden)
      else -> compare(r.name, golden, image)
    }
    if (config.accessibility && image != null && outcome !is Outcome.Missing) {
      outcome = withSemantics(r, outcome)
    }
    return VerifyResult(r.name, golden, outcome)
  }

  /** Adds the accessibility verdict: a changed or missing snapshot fails an otherwise passing image. */
  private fun withSemantics(r: Rendered, imageOutcome: Outcome): Outcome {
    val golden = r.semanticsGolden
    val actual = AccessibilityTree.normalize(r.tree!!.readText())
    val problem = when {
      !golden.exists() -> "no accessibility snapshot (${golden.name}). Run medusaRecord."
      else -> AccessibilityTree.firstDifference(golden.readText(), actual)?.let { "accessibility tree changed, $it" }
    } ?: return imageOutcome
    val base = r.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    // Kept beside the image failures so the new snapshot can be diffed against the golden by hand.
    File(File(outputFor(r.golden), "failures"), "$base.semantics.actual.txt").also { it.parentFile.mkdirs() }.writeText(actual)
    return when (imageOutcome) {
      Outcome.Passed -> Outcome.Failed(problem, null, null)
      is Outcome.Failed -> imageOutcome.copy(message = "${imageOutcome.message}; $problem")
      is Outcome.Missing -> imageOutcome
    }
  }

  private fun compare(name: String, golden: File, actual: File): Outcome {
    val result = comparer.compare(golden, actual)
    if (result !is Comparison.Failed) return Outcome.Passed
    val failuresDir = File(outputFor(golden), "failures")
    val base = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    val delta = File(failuresDir, "delta-$base.png").also { Images.write(result.delta, it) }
    val actualCopy = File(failuresDir, "${base}_actual.png").also { actual.copyTo(it, overwrite = true) }
    return Outcome.Failed(result.reason, delta, actualCopy)
  }

  /**
   * Renders [snapshots] and runs [handle] on every image **while the renderer is still producing the others**:
   *
   * ```
   * renderer(s) --emit complete PNGs--> Channel --> N comparers (Dispatchers.Default) --> results
   * ```
   * [handle] runs concurrently and must be thread-safe. Results are returned in a deterministic order
   * (snapshot order, then image index) regardless of completion order.
   */
  private fun <R> pipeline(snapshots: List<Snapshot>, handle: (Rendered) -> R): List<Pair<Rendered, R>> {
    if (snapshots.isEmpty()) return emptyList()
    renderedDir.deleteRecursively()
    renderedDir.mkdirs()

    val byFqn = snapshots.associateBy { it.preview.methodFqn }
    val position = snapshots.withIndex().associate { it.value.preview.methodFqn to it.index }
    val seen = ConcurrentHashMap.newKeySet<String>()
    val fallbackIndex = ConcurrentHashMap<String, Int>()
    val chunkSize = max(1, ceil(snapshots.size / max(1, config.renderParallelism).toDouble()).toInt())
    val chunks = snapshots.chunked(chunkSize)
    val compareCount = max(1, config.compareParallelism)

    fun toRendered(r: RenderedPreview): Rendered? {
      val snapshot = byFqn[r.methodFqn] ?: return null
      val index = r.index ?: (fallbackIndex.merge(r.methodFqn, 1, Int::plus)!! - 1)
      seen += r.methodFqn
      val image = r.imagePath?.let(::File)?.takeIf { it.isFile }
      val tree = r.componentTreePath?.let(::File)?.takeIf { it.isFile }
      var error = r.error?.let { "${it.status ?: "ERROR"}: ${it.message}" }
        ?: if (image == null) "no image was rendered" else null
      if (error == null && config.accessibility && tree == null) error = "no component tree was rendered for the accessibility snapshot"
      if (error == null && index > 0 && !snapshot.indexed) {
        error = "the CLI rendered several images (index $index) for a preview that `preview list` reported once"
      }
      return Rendered(snapshot, index, image.takeIf { error == null }, tree, error)
    }

    val started = System.nanoTime()
    val firstCompared = AtomicLong(-1)
    val lastRendered = AtomicLong(-1)
    val processingNanos = AtomicLong()
    val sendNanos = AtomicLong()
    val readyAt = ConcurrentHashMap<Rendered, Long>()
    val queueNanos = AtomicLong()
    fun sinceStart() = (System.nanoTime() - started) / 1_000_000

    val handled = runBlocking {
      val rendered = Channel<Rendered>(Channel.BUFFERED)
      coroutineScope {
        val comparers = List(compareCount) {
          async(Dispatchers.Default) {
            buildList {
              for (r in rendered) {
                firstCompared.compareAndSet(-1, sinceStart())
                val processingStarted = System.nanoTime()
                readyAt.remove(r)?.let { queueNanos.addAndGet(processingStarted - it) }
                add(r to handle(r))
                processingNanos.addAndGet(System.nanoTime() - processingStarted)
              }
            }
          }
        }
        chunks.mapIndexed { i, chunk ->
          launch(Dispatchers.IO) {
            val out = File(renderedDir, "$i")
            config.backend.render(chunk.map { it.preview.methodFqn }, out).collect { preview ->
              toRendered(preview)?.let {
                val ready = System.nanoTime()
                readyAt[it] = ready
                rendered.send(it)
                sendNanos.addAndGet(System.nanoTime() - ready)
              }
            }
          }
        }.joinAll()
        lastRendered.set(sinceStart())
        rendered.close()
        comparers.awaitAll().flatten()
      }
    }
    val completedMillis = sinceStart()
    config.log(
      "Medusa pipeline: ${handled.size} image(s); first image compared after ${firstCompared.get()} ms, " +
        "render stream closed after ${lastRendered.get()} ms, all done after $completedMillis ms; " +
        "post-render drain=${completedMillis - lastRendered.get()} ms, " +
        "processing worker-sum=${processingNanos.get() / 1_000_000} ms, " +
        "ready-to-processing sum=${queueNanos.get() / 1_000_000} ms, " +
        "send sum=${sendNanos.get() / 1_000_000} ms, compare workers=$compareCount",
    )

    // A requested preview that produced no result at all must not pass silently.
    val missing = snapshots.filter { it.preview.methodFqn !in seen }.map {
      Rendered(it, 0, null, null, "the CLI returned no result for this preview").let { r -> r to handle(r) }
    }
    return (handled + missing).sortedWith(compareBy({ position.getValue(it.first.snapshot.preview.methodFqn) }, { it.first.index }))
  }

  /** A preview that now renders fewer images must not leave the extra goldens behind. */
  private fun removeStaleIndexedGoldens(rendered: List<Rendered>) {
    val highest = rendered.filter { it.snapshot.indexed }.groupBy { it.snapshot }.mapValues { (_, r) -> r.maxOf { it.index } }
    for ((snapshot, last) in highest) {
      snapshot.existingGoldens().filter { f -> Regex("""_(\d+)\.(png|semantics\.txt)$""").find(f.name)!!.groupValues[1].toInt() > last }
        .forEach { it.delete() }
    }
  }

  /** Golden-looking PNGs (`<Stem>_*.png` beside `<Stem>.kt`) that no preview produces any more. */
  private fun findOrphans(expected: Set<File>): List<File> {
    val stemsByDirectory = mutableMapOf<File, List<String>>()
    return config.sourceRoots.asSequence()
      .filter { it.isDirectory }
      .flatMap { it.walkTopDown().filter { f -> f.isFile && (f.extension == "png" || f.name.endsWith(".semantics.txt")) } }
      .filter { golden ->
        val stems = stemsByDirectory.getOrPut(golden.parentFile) {
          golden.parentFile.listFiles { f -> f.extension == "kt" }.orEmpty().map { it.nameWithoutExtension }
        }
        stems.any { golden.name.startsWith("${it}_") } && golden.canonicalFile !in expected
      }
      .toList()
  }

  private fun unlocatedMessage(preview: PreviewInfo) =
    "${preview.methodFqn}: could not find the .kt file that declares ${preview.name} in " +
      config.sourceRoots.joinToString { it.path }
}
