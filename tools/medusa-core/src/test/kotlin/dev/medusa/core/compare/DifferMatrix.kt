package dev.medusa.core.compare

import dev.medusa.core.Comparison
import dev.medusa.core.MedusaException
import java.io.File
import java.nio.file.Files

/**
 * Prints (markdown) which differ flags which image in a corpus directory, and how long a comparison takes.
 * Run through `benchmark/differs.py`, which builds the corpus. Arguments: corpus directory.
 */
fun main(args: Array<String>) {
  val corpus = File(args.getOrNull(0) ?: error("usage: DifferMatrix <corpus dir>"))
  val base = File(corpus, "base.png")
  val work = Files.createTempDirectory("differ-matrix").toFile()

  val specs = linkedMapOf(
    "offbytwo (Paparazzi default)" to ComparerSpec(differ = "offbytwo"),
    "pixelperfect" to ComparerSpec(differ = "pixelperfect"),
    "yiq t=0.1" to ComparerSpec(differ = "yiq"),
    "yiq t=0.1 aa" to ComparerSpec(differ = "yiq", yiq = YiqOptions(antialiasing = true)),
    "yiq t=0" to ComparerSpec(differ = "yiq", yiq = YiqOptions(threshold = 0.0)),
    "odiff t=0.1" to ComparerSpec(differ = "odiff"),
    "odiff t=0.1 aa" to ComparerSpec(differ = "odiff", odiff = OdiffOptions(antialiasing = true)),
    "magick AE fuzz 2%" to ComparerSpec(differ = "magick"),
    "magick RMSE" to ComparerSpec(differ = "magick", magick = MagickOptions(metric = "RMSE", fuzz = null)),
  )
  val available = specs.filter { (_, s) ->
    runCatching { ImageComparers.create(s, work, 1).close() }.isSuccess
  }
  val skipped = specs.keys - available.keys
  val files = corpus.listFiles { f -> f.extension == "png" && f.name != "base.png" }!!.sortedBy { it.name }

  fun verdict(c: ImageComparer, f: File): String = try {
    when (val r = c.compare(base, f)) {
      is Comparison.Passed -> "pass"
      is Comparison.Failed -> if ("size changed" in r.reason) "FAIL size" else "FAIL %.2f%%".format(r.percentDifference)
    }
  } catch (e: MedusaException) {
    "error"
  }

  println("| image | " + available.keys.joinToString(" | ") + " |")
  println("|---|" + available.keys.joinToString("|") { "---" } + "|")
  val comparers = available.mapValues { (_, s) -> ImageComparers.create(s, work, 1) }
  for (f in files) {
    println("| ${f.nameWithoutExtension} | " + comparers.values.joinToString(" | ") { verdict(it, f) } + " |")
  }

  // Time per comparison on one pair, single threaded, after a warm-up.
  val pair = File(corpus, "shift1.png").takeIf { it.isFile } ?: files.first()
  println()
  println("| comparer | ms per comparison |")
  println("|---|---|")
  val timed = available.toMutableMap()
  if ("odiff t=0.1" in available) timed["odiff t=0.1, one process each"] = ComparerSpec(differ = "odiff", odiff = OdiffOptions(useServer = false))
  for ((name, spec) in timed) {
    val c = ImageComparers.create(spec, work, 1)
    repeat(20) { c.compare(base, pair) }
    val n = if ("one process" in name || "magick" in name) 100 else 300
    val t = System.nanoTime()
    repeat(n) { c.compare(base, pair) }
    println("| $name | ${"%.1f".format((System.nanoTime() - t) / 1e6 / n)} |")
    c.close()
  }
  comparers.values.forEach { it.close() }
  if (skipped.isNotEmpty()) println("\n(not run, tool missing: ${skipped.joinToString()})")
}
