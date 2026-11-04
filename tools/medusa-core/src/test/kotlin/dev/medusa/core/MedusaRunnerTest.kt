package dev.medusa.core

import app.cash.paparazzi.internal.differs.PixelPerfect
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stands in for the Android CLI: "renders" solid-color PNGs whose color the test controls.
 * A preview with a parameter provider renders 2 images; a FQN listed N times (multipreview) renders N.
 */
private class FakeBackend(private val previews: List<PreviewInfo>) : PreviewBackend {
  val colors = mutableMapOf<String, Int>()
  var failing = setOf<String>()
  val renderCalls: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
  var imageCountOverride = mutableMapOf<String, Int>()
  var reverse = false

  /** When set, every image also gets a component tree file whose semantics text is `treeText(fqn)`. */
  var treeText: ((String) -> String)? = null

  /** After this many emitted images, [gate] must complete before the render continues. */
  var gateAfter = Int.MAX_VALUE
  val gate = CompletableDeferred<Unit>()

  fun imagesFor(fqn: String): Int = imageCountOverride[fqn] ?: run {
    // Like the real CLI: one image per annotation entry, and per entry one per provider value.
    val entries = previews.filter { it.methodFqn == fqn }
    entries.size * (if (entries.first().hasParameterProvider) 2 else 1)
  }

  override fun list() = ListResult(success = true, previews = previews)

  override fun render(fqns: List<String>, outputDir: File): Flow<RenderedPreview> = flow {
    renderCalls += fqns
    var emitted = 0
    for (fqn in if (reverse) fqns.reversed() else fqns) {
      val name = fqn.substringAfterLast('.')
      if (fqn in failing) {
        emit(RenderedPreview(name, fqn, error = RenderError("RENDER_ERROR", "boom")))
        continue
      }
      for (i in 0 until imagesFor(fqn)) {
        val file = File(outputDir, "${fqn.replace('.', '/')}_$i.png")
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val color = (colors[fqn] ?: 0xFF112233.toInt()) + i * 0x10000
        for (y in 0 until 8) for (x in 0 until 8) img.setRGB(x, y, color)
        Images.write(img, file)
        val tree = treeText?.let { text ->
          File(file.path.removeSuffix(".png") + ".json").also { it.writeText(treeJson(text(fqn))) }
        }
        emit(RenderedPreview(name, fqn, imagePath = file.absolutePath, componentTreePath = tree?.absolutePath))
        if (++emitted == gateAfter) gate.await()
      }
    }
  }
}

private fun treeJson(text: String) =
  """{ "components": [ { "name": "Box", "bounds": { "left": 1, "top": 2, "right": 3, "bottom": 4 },
     "semantics": { "role": "Button" }, "children": [ { "name": "Text", "semantics": { "text": "$text" } } ] } ] }"""

class MedusaRunnerTest {
  private val src = createTempDirectory("medusa-src").toFile()
  private val out = createTempDirectory("medusa-out").toFile()

  private val button = File(src, "dev/app/Button.kt").also {
    it.parentFile.mkdirs()
    it.writeText(
      "package dev.app\nfun PrimaryPreview() {}\nfun LabelsPreview() {}\nfun DarkPreview() {}\nfun ThemesPreview() {}",
    )
  }
  private val primary = PreviewInfo("dev.app.ButtonKt.PrimaryPreview")
  private val dark = PreviewInfo("dev.app.ButtonKt.DarkPreview")
  private val labels = PreviewInfo("dev.app.ButtonKt.LabelsPreview", parameterProvider = ParameterProvider("p"))
  private val themes = PreviewInfo("dev.app.ButtonKt.ThemesPreview", previewParams = mapOf("name" to "Light"))
  private val backend = FakeBackend(listOf(primary, dark, labels, themes, themes.copy(previewParams = mapOf("name" to "Dark"))))

  private fun config(
    filter: List<String> = emptyList(),
    maxPercent: Double = DEFAULT_MAX_PERCENT_DIFFERENCE,
    differ: app.cash.paparazzi.Differ = Differs.byName(null),
    renderParallelism: Int = 1,
    compareParallelism: Int = 2,
    be: PreviewBackend = backend,
    accessibility: Boolean = false,
    failOnNoMatch: Boolean = true,
    notices: MutableList<String>? = null,
  ) = MedusaConfig(
    be, listOf(src), out, maxPercent, differ, PreviewFilter(filter), renderParallelism, compareParallelism, accessibility,
    failOnNoMatch = failOnNoMatch,
    notice = { notices?.add(it) },
  )

  private fun runner(filter: List<String> = emptyList(), maxPercent: Double = DEFAULT_MAX_PERCENT_DIFFERENCE) =
    MedusaRunner(config(filter, maxPercent))

  private fun golden(name: String) = File(button.parentFile, name)

  private val allGoldens = listOf(
    "Button_PrimaryPreview.png",
    "Button_DarkPreview.png",
    "Button_LabelsPreview_0.png",
    "Button_LabelsPreview_1.png",
    "Button_ThemesPreview_0.png",
    "Button_ThemesPreview_1.png",
  )

  @Test
  fun `record writes goldens next to the source file, indexed for parameter providers and multipreviews`() {
    val summary = runner().record()

    assertTrue(summary.ok, summary.errors.toString())
    assertEquals(6, summary.recorded.size)
    assertTrue(summary.recorded.all { it.status == RecordStatus.NEW })
    allGoldens.forEach { assertTrue(golden(it).isFile, "$it should exist") }
    assertFalse(golden("Button_ThemesPreview.png").exists(), "a multipreview must never use the unindexed name")
  }

  @Test
  fun `every entry of a multipreview gets its own golden with its own pixels`() {
    runner().record()

    val first = Images.read(golden("Button_ThemesPreview_0.png")).getRGB(0, 0)
    val second = Images.read(golden("Button_ThemesPreview_1.png")).getRGB(0, 0)
    assertTrue(first != second, "entries must not overwrite each other")
  }

  @Test
  fun `record twice reports unchanged and does not rewrite files`() {
    runner().record()
    val before = golden("Button_PrimaryPreview.png").lastModified()
    Thread.sleep(20)

    val second = runner().record()

    assertTrue(second.recorded.all { it.status == RecordStatus.UNCHANGED })
    assertEquals(before, golden("Button_PrimaryPreview.png").lastModified())
  }

  @Test
  fun `verify passes right after record`() {
    runner().record()

    val summary = runner().verify()

    assertTrue(summary.ok, summary.failures.toString())
    assertEquals(6, summary.results.size)
    assertTrue(summary.reportFile.isFile)
    assertTrue(File(out, "test-results/medusa.xml").readText().contains("tests=\"6\" failures=\"0\""))
  }

  @Test
  fun `verify fails when a golden is missing`() {
    val summary = runner().verify()

    assertFalse(summary.ok)
    assertTrue(summary.results.all { it.outcome is Outcome.Missing })
  }

  @Test
  fun `verify fails on a real change and writes Paparazzi's delta image and the actual rendering`() {
    runner().record()
    backend.colors[primary.methodFqn] = 0xFFFF0000.toInt()

    val summary = runner().verify()

    val failure = summary.failures.single()
    assertEquals("dev.app.ButtonKt.PrimaryPreview", failure.name)
    val outcome = assertIs<Outcome.Failed>(failure.outcome)
    assertTrue(outcome.message.startsWith("Images differ (by "), outcome.message)
    val delta = Images.read(outcome.delta!!)
    assertEquals(8 + 8 + 8, delta.width, "delta image is expected | delta | actual")
    assertTrue(outcome.actual!!.isFile)
    assertTrue(File(out, "test-results/medusa.xml").readText().contains("failures=\"1\""))
  }

  @Test
  fun `default differ ignores a change of one color level but a pixel perfect one does not`() {
    runner().record()
    backend.colors[primary.methodFqn] = 0xFF112233.toInt() + 1 // blue +1 on every pixel

    assertTrue(runner().verify().ok, "OffByTwo (Paparazzi's default) treats off-by-one as similar")

    val strict = MedusaRunner(config(maxPercent = 0.0, differ = PixelPerfect)).verify()
    assertFalse(strict.ok)
    assertTrue(MedusaRunner(config(maxPercent = 1.0, differ = PixelPerfect)).verify().ok)
  }

  @Test
  fun `record keeps a golden whose rendering is within tolerance`() {
    runner().record()
    val before = golden("Button_PrimaryPreview.png").lastModified()
    backend.colors[primary.methodFqn] = 0xFF112233.toInt() + 1
    Thread.sleep(20)

    val second = runner().record()

    assertTrue(second.recorded.all { it.status == RecordStatus.UNCHANGED })
    assertEquals(before, golden("Button_PrimaryPreview.png").lastModified())
  }

  @Test
  fun `filter limits both what is rendered and what is recorded`() {
    val summary = runner(filter = listOf("PrimaryPreview")).record()

    assertEquals(listOf(listOf(primary.methodFqn)), backend.renderCalls)
    assertEquals(1, summary.recorded.size)
    assertTrue(golden("Button_PrimaryPreview.png").isFile)
    assertFalse(golden("Button_DarkPreview.png").exists())
  }

  @Test
  fun `filter that matches nothing is an error listing the available previews`() {
    val e = assertFailsWith<MedusaException> { runner(filter = listOf("Nope")).verify() }

    assertTrue("PrimaryPreview" in e.message!!)
  }

  @Test
  fun `render failure is reported and does not stop other previews`() {
    backend.failing = setOf(dark.methodFqn)

    val record = runner().record()
    assertFalse(record.ok)
    assertTrue("boom" in record.errors.single())
    assertTrue(golden("Button_PrimaryPreview.png").isFile)

    val verify = runner().verify()
    assertEquals(1, verify.failures.filter { it.outcome is Outcome.Failed }.size)
  }

  @Test
  fun `orphaned goldens are reported only for a full verify`() {
    runner().record()
    val orphan = golden("Button_RemovedPreview.png").also { it.writeBytes(byteArrayOf(1)) }
    val unrelated = File(button.parentFile, "logo.png").also { it.writeBytes(byteArrayOf(1)) }

    val full = runner().verify()
    val filtered = runner(filter = listOf("PrimaryPreview")).verify()

    assertEquals(listOf(orphan.canonicalFile), full.orphans.map { it.canonicalFile })
    assertTrue(unrelated.canonicalFile !in full.orphans.map { it.canonicalFile })
    assertTrue(filtered.orphans.isEmpty())
  }

  @Test
  fun `delete removes only the matching goldens including every index`() {
    runner().record()

    val deleted = runner(filter = listOf("LabelsPreview")).delete()

    assertEquals(2, deleted.size)
    assertFalse(golden("Button_LabelsPreview_0.png").exists())
    assertFalse(golden("Button_LabelsPreview_1.png").exists())
    assertTrue(golden("Button_PrimaryPreview.png").isFile)
    assertTrue(golden("Button_ThemesPreview_0.png").isFile)
  }

  @Test
  fun `record removes indexed goldens that a preview no longer produces`() {
    runner().record()
    backend.imageCountOverride[labels.methodFqn] = 1

    runner().record()

    assertTrue(golden("Button_LabelsPreview_0.png").isFile)
    assertFalse(golden("Button_LabelsPreview_1.png").exists(), "index 1 is stale")
  }

  @Test
  fun `a preview whose source cannot be found fails verify`() {
    val ghost = PreviewInfo("dev.ghost.GhostKt.GhostPreview")
    val b = FakeBackend(listOf(primary, ghost))

    val summary = MedusaRunner(config(be = b)).verify()

    assertTrue(summary.failures.any { it.name == ghost.methodFqn })
  }

  @Test
  fun `a preview the renderer silently skipped fails instead of passing`() {
    backend.imageCountOverride[dark.methodFqn] = 0

    val summary = runner().verify()

    val failure = summary.failures.single { it.name == dark.methodFqn }
    assertIs<Outcome.Failed>(failure.outcome)
  }

  @Test
  fun `an unexpected extra image for a single preview is an error`() {
    backend.imageCountOverride[primary.methodFqn] = 2

    val summary = runner().record()

    assertTrue(summary.errors.any { "PrimaryPreview" in it && "several images" in it }, summary.errors.toString())
  }

  // ---------------------------------------------------------------- multi-module and naming

  @Test
  fun `a filter that matches nothing is skipped with a notice when the task runs in every module`() {
    val notices = mutableListOf<String>()

    val verify = MedusaRunner(config(filter = listOf("Nope"), failOnNoMatch = false, notices = notices)).verify()
    val record = MedusaRunner(config(filter = listOf("Nope"), failOnNoMatch = false)).record()

    assertTrue(verify.ok && verify.results.isEmpty())
    assertTrue(record.ok && record.recorded.isEmpty())
    assertEquals(1, notices.size)
    assertTrue("Skipped" in notices.single() && "--preview" in notices.single(), notices.toString())
    assertTrue(backend.renderCalls.isEmpty(), "nothing is rendered when nothing matches")
  }

  @Test
  fun `a module with no previews verifies and records cleanly`() {
    val empty = FakeBackend(emptyList())

    val verify = MedusaRunner(config(be = empty)).verify()
    val record = MedusaRunner(config(be = empty)).record()

    assertTrue(verify.ok && verify.results.isEmpty())
    assertTrue(record.ok && record.recorded.isEmpty())
    assertTrue(empty.renderCalls.isEmpty())
  }

  @Test
  fun `same function name in two classes of one file gets the class name in the golden`() {
    File(src, "dev/app/Screens.kt").writeText("package dev.app\nobject Light { fun Preview() {} }\nobject Dark { fun Preview() {} }\nfun Unique() {}")
    val light = PreviewInfo("dev.app.Light.Preview")
    val darkP = PreviewInfo("dev.app.Dark.Preview")
    val unique = PreviewInfo("dev.app.ScreensKt.Unique")
    val b = FakeBackend(listOf(light, darkP, unique))
    val screens = File(src, "dev/app")

    val summary = MedusaRunner(config(be = b)).record()

    assertTrue(summary.ok, summary.errors.toString())
    assertTrue(File(screens, "Screens_Light_Preview.png").isFile)
    assertTrue(File(screens, "Screens_Dark_Preview.png").isFile)
    assertTrue(File(screens, "Screens_Unique.png").isFile, "a preview without a collision keeps the plain name")
    assertFalse(File(screens, "Screens_Preview.png").exists(), "the colliding plain name must not be used")
    assertTrue(MedusaRunner(config(be = b)).verify().ok)

    // The name must not depend on the filter: recording one of the colliding previews keeps its qualified name.
    File(screens, "Screens_Light_Preview.png").delete()
    MedusaRunner(config(be = b, filter = listOf("Light.Preview"))).record()
    assertTrue(File(screens, "Screens_Light_Preview.png").isFile)
    assertFalse(File(screens, "Screens_Preview.png").exists())
    val deleted = MedusaRunner(config(be = b, filter = listOf("Light.Preview"))).delete()
    assertEquals(listOf("Screens_Light_Preview.png"), deleted.map { it.name })
  }

  @Test
  fun `a custom multipreview with a parameter provider gets one golden per entry and value`() {
    File(src, "dev/app/Custom.kt").writeText("package dev.app\nfun WidthsLabels() {}")
    val entry = PreviewInfo("dev.app.CustomKt.WidthsLabels", parameterProvider = ParameterProvider("p"))
    val b = FakeBackend(listOf(entry, entry, entry)) // three annotation entries, two provider values each = 6 images

    val record = MedusaRunner(config(be = b)).record()

    assertTrue(record.ok, record.errors.toString())
    assertEquals(6, record.recorded.size)
    for (i in 0..5) assertTrue(File(src, "dev/app/Custom_WidthsLabels_$i.png").isFile, "index $i")
    assertFalse(File(src, "dev/app/Custom_WidthsLabels.png").exists())
    assertTrue(MedusaRunner(config(be = b)).verify().ok)
  }

  // ---------------------------------------------------------------- accessibility

  private fun a11y() = MedusaRunner(config(accessibility = true))

  @Test
  fun `accessibility snapshots are recorded next to the image and verified`() {
    backend.treeText = { "Primary" }

    val record = a11y().record()

    assertTrue(record.ok, record.errors.toString())
    val text = golden("Button_PrimaryPreview.semantics.txt").readText()
    assertTrue(text.startsWith(AccessibilityTree.HEADER))
    assertTrue("Text text=\"Primary\"" in text, text)
    assertTrue(golden("Button_LabelsPreview_1.semantics.txt").isFile)
    assertTrue(a11y().verify().ok)
  }

  @Test
  fun `a changed accessibility tree fails even when the image is identical`() {
    backend.treeText = { "Primary" }
    a11y().record()
    backend.treeText = { fqn -> if (fqn == primary.methodFqn) "Renamed" else "Primary" }

    val summary = a11y().verify()

    val failure = summary.failures.single()
    assertEquals("dev.app.ButtonKt.PrimaryPreview", failure.name)
    val outcome = assertIs<Outcome.Failed>(failure.outcome)
    assertTrue("accessibility tree changed" in outcome.message && "Renamed" in outcome.message, outcome.message)
    assertTrue(File(out, "failures/dev.app.ButtonKt.PrimaryPreview.semantics.actual.txt").isFile)
  }

  @Test
  fun `a missing accessibility snapshot fails verify and tells how to fix it`() {
    backend.treeText = { "Primary" }
    runner().record() // images only, no snapshots

    val summary = a11y().verify()

    assertEquals(6, summary.failures.size)
    assertTrue(summary.failures.all { (it.outcome as Outcome.Failed).message.contains("Run medusaRecord") })
  }

  @Test
  fun `accessibility off ignores snapshots and reports stale ones as orphans`() {
    backend.treeText = { "Primary" }
    a11y().record()

    val summary = runner().verify()

    assertTrue(summary.ok)
    assertEquals(6, summary.orphans.size)
    assertTrue(summary.orphans.all { it.name.endsWith(".semantics.txt") })
  }

  @Test
  fun `record notices a changed tree under an identical image`() {
    backend.treeText = { "Primary" }
    a11y().record()
    backend.treeText = { "Changed" }

    val second = a11y().record()

    assertTrue(second.recorded.all { it.status == RecordStatus.CHANGED })
    assertTrue("Changed" in golden("Button_PrimaryPreview.semantics.txt").readText())
  }

  @Test
  fun `delete and stale cleanup cover the accessibility snapshots too`() {
    backend.treeText = { "Primary" }
    a11y().record()
    backend.imageCountOverride[labels.methodFqn] = 1
    a11y().record()
    assertFalse(golden("Button_LabelsPreview_1.semantics.txt").exists(), "stale index 1 snapshot is removed")

    val deleted = MedusaRunner(config(filter = listOf("PrimaryPreview"))).delete()

    assertEquals(listOf("Button_PrimaryPreview.png", "Button_PrimaryPreview.semantics.txt"), deleted.map { it.name }.sorted())
  }

  @Test
  fun `accessibility without a component tree from the renderer is an error`() {
    // treeText stays null: the fake renders no component tree
    val record = a11y().record()

    assertFalse(record.ok)
    assertTrue(record.errors.all { "component tree" in it }, record.errors.toString())
  }

  // ---------------------------------------------------------------- pipeline

  @Test
  fun `comparison starts while the renderer is still rendering`() {
    runner().record()
    backend.colors[primary.methodFqn] = 0xFFFF0000.toInt() // the first image to arrive will fail verification
    backend.gateAfter = 1 // and the render pauses right after emitting it
    var result: VerifySummary? = null
    val worker = thread { result = runner().verify() }

    // The failure artifact is written by the comparer, so it can only exist while the render is
    // still paused if the comparison ran concurrently with rendering.
    val delta = File(out, "failures/delta-dev.app.ButtonKt.PrimaryPreview.png")
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline && !delta.isFile) Thread.sleep(10)
    val comparedWhileRendering = delta.isFile && worker.isAlive
    backend.gate.complete(Unit)
    worker.join(10_000)

    assertTrue(comparedWhileRendering, "the first image must be compared while the renderer is still running")
    assertFalse(worker.isAlive, "verify must finish once the render continues")
    assertEquals(1, result!!.failures.size)
  }

  @Test
  fun `record writes a golden before the renderer has finished`() {
    backend.gateAfter = 1
    val worker = thread { runner().record() }

    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline && allGoldens.none { golden(it).isFile }) Thread.sleep(10)
    val existedWhileRendering = allGoldens.count { golden(it).isFile }
    backend.gate.complete(Unit)
    worker.join(10_000)

    assertEquals(1, existedWhileRendering, "exactly the first image is recorded while the render is paused")
    assertEquals(6, allGoldens.count { golden(it).isFile })
  }

  @Test
  fun `results are in a deterministic order whatever the completion order`() {
    runner().record()
    val forward = runner().verify().results.map { it.name }

    backend.reverse = true
    val reversed = MedusaRunner(config(compareParallelism = 4)).verify().results.map { it.name }

    val expected = listOf(
      "dev.app.ButtonKt.PrimaryPreview",
      "dev.app.ButtonKt.DarkPreview",
      "dev.app.ButtonKt.LabelsPreview[0]",
      "dev.app.ButtonKt.LabelsPreview[1]",
      "dev.app.ButtonKt.ThemesPreview[0]",
      "dev.app.ButtonKt.ThemesPreview[1]",
    )
    assertEquals(expected, forward)
    assertEquals(expected, reversed)
  }

  @Test
  fun `render parallelism splits the previews into disjoint batches that cover everything`() {
    val summary = MedusaRunner(config(renderParallelism = 2)).record()

    assertTrue(summary.ok, summary.errors.toString())
    assertEquals(2, backend.renderCalls.size)
    val all = backend.renderCalls.flatten()
    assertEquals(all.toSet().size, all.size, "no preview is rendered twice")
    assertEquals(setOf(primary, dark, labels, themes).map { it.methodFqn }.toSet(), all.toSet())
    assertEquals(6, summary.recorded.size)
  }

  @Test
  fun `a single compare worker still handles everything`() {
    val summary = MedusaRunner(config(compareParallelism = 1)).record()

    assertEquals(6, summary.recorded.size)
  }
}
