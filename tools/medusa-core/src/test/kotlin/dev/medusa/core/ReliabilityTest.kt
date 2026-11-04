package dev.medusa.core

import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.flow.flow

class ReliabilityTest {
  private val dir = createTempDirectory("medusa-reliability").toFile()
  private val source = File(dir, "src/fx/Screen.kt").apply { parentFile.mkdirs(); writeText("package fx\nfun Preview() {}") }
  private val output = File(dir, "build/medusa")
  private val calls = AtomicInteger()
  private var colors = listOf(0xffff0000.toInt())
  private var entries = 1
  private var indices = listOf(0)
  private val backend = object : PreviewBackend {
    override fun list() = ListResult(true, previews = List(entries) { PreviewInfo("fx.ScreenKt.Preview") })
    override fun render(fqns: List<String>, outputDir: File) = flow {
      val call = calls.getAndIncrement()
      for (index in indices) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (x in 0..7) for (y in 0..7) image.setRGB(x, y, colors[call % colors.size])
        val file = File(outputDir, "fx/ScreenKt/Preview_$index.png").apply { parentFile.mkdirs() }
        ImageIO.write(image, "png", file)
        emit(RenderedPreview("preview", "fx.ScreenKt.Preview", file.path))
      }
    }
  }
  private fun runner(options: ReliabilityOptions = ReliabilityOptions()) = MedusaRunner(MedusaConfig(
    backend, listOf(File(dir, "src")), output, reliability = options,
  ))
  private fun mismatch() { colors = listOf(0xff0000ff.toInt(), 0xffff0000.toInt()); calls.set(0) }

  @Test fun `ordinary passing verification renders once`() {
    assertTrue(runner().record().ok)
    calls.set(0)
    assertTrue(runner().verify().ok)
    assertEquals(1, calls.get())
  }
  @Test fun `recovery is opt in and keeps original failure evidence`() {
    runner().record(); mismatch()
    val result = runner(ReliabilityOptions(visualRetryCount = 1, allowRecoveredPass = true)).verify()
    assertTrue(result.ok)
    val preview = result.results.single()
    assertTrue(preview.recovered)
    assertEquals(2, preview.attempts.size)
    val first = preview.attempts.first().outcome as Outcome.Failed
    assertTrue(first.actual!!.isFile)
    assertTrue(first.delta!!.isFile)
    assertTrue(File(output, "test-results/medusa.xml").readText().contains("FLAKY"))
    assertTrue(File(output, "results.json").readText().contains("recovered"))
  }
  @Test fun `diagnostic retry cannot silently recover strict verdict`() {
    runner().record(); mismatch()
    val result = runner(ReliabilityOptions(visualRetryCount = 1)).verify()
    assertFalse(result.ok)
    assertFalse(result.results.single().recovered)
    assertIs<Outcome.Passed>(result.results.single().attempts.last().outcome)
  }
  @Test fun `missing goldens are never visually retried`() {
    val result = runner(ReliabilityOptions(visualRetryCount = 3, allowRecoveredPass = true)).verify()
    assertFalse(result.ok)
    assertEquals(1, calls.get())
  }
  @Test fun `known omitted variant and duplicate results fail recording without overwriting goldens`() {
    entries = 2; indices = listOf(0, 1); runner().record()
    val before = source.parentFile.listFiles()!!.filter { it.extension == "png" }.associate { it.name to it.readBytes().toList() }
    indices = listOf(0)
    assertFalse(runner().record().ok)
    assertFalse(runner().verify().ok)
    indices = listOf(0, 0)
    assertFalse(runner().record().ok)
    for ((name, bytes) in before) assertEquals(bytes, File(source.parentFile, name).readBytes().toList())
  }
  @Test fun `diagnosis detects unstable renders without changing goldens`() {
    runner().record()
    val golden = File(source.parentFile, "Screen_Preview.png")
    val before = golden.readBytes()
    mismatch()
    assertFalse(runner().diagnose())
    assertEquals(10, calls.get())
    assertContentEquals(before, golden.readBytes())
    assertTrue(File(output, "diagnostics.json").readText().contains("unstable"))
  }
  @Test fun `interrupted transaction restores replacement deletion and new destination`() {
    val a = File(source.parentFile, "a.png").apply { writeText("replacement") }
    val b = File(source.parentFile, "b.png")
    val c = File(source.parentFile, "c.png").apply { writeText("new") }
    val journalHome = File(source.parentFile, "transaction").apply { mkdirs() }
    File(journalHome, "backup.0").writeText("original")
    File(journalHome, "backup.1").writeText("deleted")
    val journal = File(journalHome, "manifest")
    Properties().apply {
      setProperty("count", "3")
      listOf(a,b,c).forEachIndexed { index, file -> setProperty("target.$index", file.canonicalPath) }
      journal.outputStream().use { store(it, null) }
    }
    File(source.parentFile, ".medusa-journal").writeText(journal.path)
    RunLocks.withLocks(listOf(source.parentFile)) {
      assertEquals("original", a.readText()); assertEquals("deleted", b.readText()); assertFalse(c.exists())
    }
    assertFalse(journalHome.exists())
  }
  @Test fun `configuration rejects accidental recovered pass and invalid deadlines`() {
    assertFailsWith<IllegalArgumentException> { ReliabilityOptions(allowRecoveredPass = true) }
    assertFailsWith<IllegalArgumentException> { ReliabilityOptions(renderTimeoutSeconds = 0) }
    assertFailsWith<IllegalArgumentException> { ReliabilityOptions(visualRetryCount = 4) }
  }
  @Test fun `overlapping invocations wait for the golden lock`() {
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val second = java.util.concurrent.CountDownLatch(1)
    val firstThread = kotlin.concurrent.thread {
      RunLocks.withLocks(listOf(source.parentFile)) { entered.countDown(); release.await() }
    }
    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
    val nextThread = kotlin.concurrent.thread { RunLocks.withLocks(listOf(source.parentFile)) { second.countDown() } }
    try { assertFalse(second.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) }
    finally { release.countDown() }
    assertTrue(second.await(5, java.util.concurrent.TimeUnit.SECONDS))
    firstThread.join(5000); nextThread.join(5000)
  }

  @Test fun `a commit failure restores replacements and retains deleted originals`() {
    val original = File(source.parentFile, "original.png").apply { writeText("original") }
    val staged = File(source.parentFile, "new.tmp").apply { writeText("replacement") }
    val missing = File(source.parentFile, "missing.tmp")
    val other = File(source.parentFile, "zz.png").apply { writeText("keep") }
    assertFailsWith<Exception> {
      GoldenTransaction.commit(linkedMapOf(original to staged, other to missing))
    }
    assertEquals("original", original.readText())
    assertEquals("keep", other.readText())
    assertFalse(File(source.parentFile, ".medusa-journal").exists())
  }

  @Test fun `an infrastructure failure replaces old passing reports`() {
    runner().record(); assertTrue(runner().verify().ok)
    val broken = object : PreviewBackend {
      override fun list(): ListResult = throw MedusaException("\u001b[31mtoken=do-not-print renderer unavailable\u001b[0m")
      override fun render(fqns: List<String>, outputDir: File) = backend.render(fqns, outputDir)
    }
    assertFailsWith<MedusaException> { MedusaRunner(MedusaConfig(broken, listOf(File(dir, "src")), output)).verify() }
    val report = File(output, "results.json").readText()
    assertTrue("infrastructure" in report && "failed" in report)
    assertFalse("do-not-print" in report)
    assertTrue(File(output, "test-results/medusa.xml").readText().contains("failures=\"1\""))
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(output, "test-results/medusa.xml"))
  }

  @Test fun `ignore regions cannot be silently dropped on fallback`() {
    val spec = dev.medusa.core.compare.ComparerSpec(
      odiff = dev.medusa.core.compare.OdiffOptions(ignoreRegions = listOf("0:0-1:1")),
      autoInstall = false, installDir = File(dir, "no-binary").path)
    assertFailsWith<MedusaException> {
      dev.medusa.core.compare.ImageComparers.resolveOdiff(spec, "", {}, systemDirs = emptyList())
    }
  }

  @Test fun `golden locks also serialize a separate JVM`() {
    val marker = File(dir, "child-acquired")
    val classpath = listOf(RunLocks::class.java, GoldenLockWorker::class.java, Unit::class.java)
      .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
    val javaBinary = File(System.getProperty("java.home"), "bin/java").path
    lateinit var process: Process
    RunLocks.withLocks(listOf(source.parentFile)) {
      process = ProcessBuilder(javaBinary, "-cp", classpath, GoldenLockWorker::class.java.name, source.parent, marker.path)
        .redirectErrorStream(true).start()
      try {
        assertEquals("ready", ProcessSupervisor.bounded(process, 5) { process.inputStream.bufferedReader().readLine() })
        assertFalse(process.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertFalse(marker.exists())
      } catch (failure: Throwable) { ProcessSupervisor.stop(process); throw failure }
    }
    try {
      assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
      assertEquals(0, process.exitValue())
      assertTrue(marker.exists())
    } finally { if (process.isAlive) ProcessSupervisor.stop(process) }
  }

}


object GoldenLockWorker {
  @JvmStatic fun main(args: Array<String>) {
    println("ready")
    RunLocks.withLocks(listOf(File(args[0]))) { File(args[1]).writeText("acquired") }
  }
}
