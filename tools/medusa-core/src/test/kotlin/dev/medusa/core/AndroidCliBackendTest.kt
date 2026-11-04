package dev.medusa.core

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Drives [AndroidCliBackend] against a shell script that behaves like `android preview render`. */
class AndroidCliBackendTest {
  private val dir = createTempDirectory("medusa-cli").toFile()
  private val out = File(dir, "out")

  private val png: ByteArray = ByteArrayOutputStream().also {
    ImageIO.write(BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB), "png", it)
  }.toByteArray()

  private fun script(body: String): File = File(dir, "android").also {
    File(dir, "img.png").writeBytes(png)
    it.writeText("#!/bin/bash\nhere=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n$body\n")
    it.setExecutable(true)
  }

  private fun backend(cli: File) = AndroidCliBackend(dir, "sample", cli, pollMillis = 10)

  private val fqnA = "dev.app.ButtonKt.APreview"
  private val fqnB = "dev.app.ButtonKt.BPreview"

  @Test
  fun `images are emitted as soon as they are complete, before the process ends`() {
    val cli = script(
      """
      for a in "${'$'}@"; do case "${'$'}a" in --output=*) o="${'$'}{a#--output=}";; esac; done
      mkdir -p "${'$'}o/dev/app/ButtonKt"
      cp "${'$'}here/img.png" "${'$'}o/dev/app/ButtonKt/APreview_0.png"
      sleep 2
      cp "${'$'}here/img.png" "${'$'}o/dev/app/ButtonKt/BPreview_0.png"
      printf '%s\n' '{' '  "success": true,' '  "results": [' \
        '    { "previewId": "APreview", "methodFqn": "$fqnA", "imagePath": "'"${'$'}o"'/dev/app/ButtonKt/APreview_0.png" },' \
        '    { "previewId": "BPreview", "methodFqn": "$fqnB", "imagePath": "'"${'$'}o"'/dev/app/ButtonKt/BPreview_0.png" }' \
        '  ]' '}'
      """.trimIndent(),
    )
    val start = System.nanoTime()
    val times = mutableListOf<Pair<String, Long>>()

    val all = runBlocking {
      backend(cli).render(listOf(fqnA, fqnB), out).onEach { times += it.methodFqn to (System.nanoTime() - start) / 1_000_000 }.toList()
    }
    val total = (System.nanoTime() - start) / 1_000_000

    assertEquals(listOf(fqnA, fqnB), all.map { it.methodFqn })
    assertTrue(all.all { it.error == null && File(it.imagePath!!).isFile })
    assertTrue(times[0].second < 1500, "A must arrive while the script is still sleeping, arrived at ${times[0].second} ms")
    assertTrue(total >= 2000, "the script sleeps 2s, total was $total ms")
    assertEquals(2, all.map { it.imagePath }.toSet().size, "each image is emitted exactly once")
  }

  @Test
  fun `a truncated png is not emitted until it is complete`() {
    val cli = script(
      """
      for a in "${'$'}@"; do case "${'$'}a" in --output=*) o="${'$'}{a#--output=}";; esac; done
      mkdir -p "${'$'}o/dev/app/ButtonKt"
      head -c 30 "${'$'}here/img.png" > "${'$'}o/dev/app/ButtonKt/APreview_0.png"
      sleep 1
      cp "${'$'}here/img.png" "${'$'}o/dev/app/ButtonKt/APreview_0.png"
      printf '%s\n' '{' '  "success": true,' '  "results": [' \
        '    { "previewId": "APreview", "methodFqn": "$fqnA", "imagePath": "'"${'$'}o"'/dev/app/ButtonKt/APreview_0.png" }' \
        '  ]' '}'
      """.trimIndent(),
    )
    val start = System.nanoTime()

    val first = runBlocking { backend(cli).render(listOf(fqnA), out).first() }
    val elapsed = (System.nanoTime() - start) / 1_000_000

    assertTrue(elapsed >= 900, "the half-written file must not be emitted, got it after $elapsed ms")
    assertTrue(AndroidCliBackend.isCompletePng(File(first.imagePath!!)))
    assertEquals(png.size.toLong(), File(first.imagePath!!).length())
  }

  @Test
  fun `cancelling the collector kills the render process`() {
    val cli = script(
      """
      for a in "${'$'}@"; do case "${'$'}a" in --output=*) o="${'$'}{a#--output=}";; esac; done
      mkdir -p "${'$'}o/dev/app/ButtonKt"
      cp "${'$'}here/img.png" "${'$'}o/dev/app/ButtonKt/APreview_0.png"
      echo ${'$'}${'$'} > "${'$'}here/pid"
      exec sleep 60
      """.trimIndent(),
    )

    runBlocking { backend(cli).render(listOf(fqnA), out).first() }

    val pid = File(dir, "pid").readText().trim().toLong()
    val deadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < deadline && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) Thread.sleep(50)
    assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "render process $pid must be gone after cancellation")
  }

  @Test
  fun `results the watcher cannot attribute and per-preview errors come from the JSON`() {
    val cli = script(
      """
      for a in "${'$'}@"; do case "${'$'}a" in --output=*) o="${'$'}{a#--output=}";; esac; done
      mkdir -p "${'$'}o"
      cp "${'$'}here/img.png" "${'$'}o/odd name.png"
      printf '%s\n' '{' '  "success": false,' '  "results": [' \
        '    { "previewId": "APreview", "methodFqn": "$fqnA", "imagePath": "'"${'$'}o"'/odd name.png" },' \
        '    { "previewId": "BPreview", "methodFqn": "$fqnB", "error": { "status": "RENDER_ERROR", "message": "boom" } }' \
        '  ]' '}'
      """.trimIndent(),
    )

    val all = runBlocking { backend(cli).render(listOf(fqnA, fqnB), out).toList() }

    assertEquals(2, all.size)
    assertTrue(File(all.single { it.methodFqn == fqnA }.imagePath!!).isFile)
    val failed = all.single { it.methodFqn == fqnB }
    assertNull(failed.imagePath)
    assertEquals("boom", failed.error?.message)
  }

  @Test
  fun `a failed render without any results is an error carrying the reason`() {
    val cli = script("""printf '%s\n' '{' '  "success": false,' '  "globalError": "Gradle exploded",' '  "results": []' '}'""")

    val e = assertFailsWith<MedusaException> { runBlocking { backend(cli).render(listOf(fqnA), out).toList() } }

    assertTrue("Gradle exploded" in e.message!!)
  }

  @Test
  fun `a crash without JSON is an error with the output`() {
    val cli = script("echo 'segfault'; exit 3")

    val e = assertFailsWith<MedusaException> { runBlocking { backend(cli).render(listOf(fqnA), out).toList() } }

    assertTrue("exited with 3" in e.message!! && "segfault" in e.message!!)
  }

  @Test
  fun `list passes the module reference and parses the output`() {
    val cli = script(
      """
      echo "${'$'}*" > "${'$'}here/args"
      printf '%s\n' 'Build log: fake' '{' '  "success": true,' '  "previews": [' \
        '    { "methodFqn": "$fqnA", "previewParams": {} }' '  ]' '}'
      """.trimIndent(),
    )

    val result = AndroidCliBackend(dir, "sample", cli, includeDependentModules = true).list()

    assertEquals(listOf(fqnA), result.previews.map { it.methodFqn })
    assertEquals("--no-metrics preview list --format=json --all sample", File(dir, "args").readText().trim())
  }

  @Test
  fun `render passes daemon, component tree and one fqn per preview`() {
    val cli = script(
      """
      echo "${'$'}*" > "${'$'}here/args"
      printf '%s\n' '{' '  "success": true,' '  "results": [' '    { "previewId": "x", "methodFqn": "$fqnA", "error": { "message": "m" } }' '  ]' '}'
      """.trimIndent(),
    )

    runBlocking { AndroidCliBackend(dir, "sample", cli, daemon = true, componentTree = true).render(listOf(fqnA, fqnB), out).toList() }

    val args = File(dir, "args").readText().trim()
    assertTrue(args.startsWith("--no-metrics preview render --format=json --output=${out.absolutePath} --daemon --component-tree"), args)
    assertTrue(args.endsWith("--fqn=$fqnA --fqn=$fqnB sample"), args)
  }

  @Test
  fun `png completeness and fqn derivation helpers`() {
    val complete = File(dir, "ok.png").also { it.writeBytes(png) }
    val cut = File(dir, "cut.png").also { it.writeBytes(png.copyOf(png.size - 5)) }
    val empty = File(dir, "empty.png").also { it.writeBytes(ByteArray(0)) }

    assertTrue(AndroidCliBackend.isCompletePng(complete))
    assertFalse(AndroidCliBackend.isCompletePng(cut))
    assertFalse(AndroidCliBackend.isCompletePng(empty))
    assertEquals(
      "dev.app.ButtonKt.APreview" to 12,
      AndroidCliBackend.fqnOf(out, File(out, "dev/app/ButtonKt/APreview_12.png")),
    )
    assertNull(AndroidCliBackend.fqnOf(out, File(out, "odd name.png")))
  }
  @Test
  fun `daemon missing class retries once without daemon`() {
    val cli = script("""
      echo "§*" >> "§here/calls"
      daemon=0
      for a in "§@"; do case "§a" in --daemon) daemon=1;; --output=*) o="§{a#--output=}";; esac; done
      echo '{'
      if [ §daemon = 1 ]; then
        echo '"success":false,"results":[{"previewId":"a","methodFqn":"$fqnA","error":{"status":"VALIDATION_ERROR","message":"Class could not be found on the classpath"}}]}'
      else
        mkdir -p "§o/dev/app/ButtonKt"
        cp "§here/img.png" "§o/dev/app/ButtonKt/APreview_0.png"
        echo '"success":true,"results":[{"previewId":"a","methodFqn":"$fqnA","imagePath":"'"§o"'/dev/app/ButtonKt/APreview_0.png"}]}'
      fi
    """.trimIndent().replace('§', '$'))
    val results = runBlocking { AndroidCliBackend(dir, "sample", cli, daemon = true).render(listOf(fqnA), out).toList() }
    assertEquals(1, results.size)
    assertNull(results.single().error)
    assertTrue(File(results.single().imagePath!!).isFile)
    val calls = File(dir, "calls").readLines()
    assertEquals(2, calls.size)
    assertTrue("--daemon" in calls[0])
    assertFalse("--daemon" in calls[1])
  }

  @Test
  fun `fresh renderer failure remains a failure and does not retry again`() {
    val cli = script("""
      echo called >> "§here/calls"
      echo '{'
      echo '"success":false,"results":[{"previewId":"a","methodFqn":"$fqnA","error":{"status":"VALIDATION_ERROR","message":"Class could not be found on the classpath"}}]}'
    """.trimIndent().replace('§', '$'))
    val results = runBlocking { AndroidCliBackend(dir, "sample", cli, daemon = true).render(listOf(fqnA), out).toList() }
    assertEquals(2, File(dir, "calls").readLines().size)
    assertEquals("VALIDATION_ERROR", results.single().error?.status)
  }

  @Test fun `discovery deadline captures and redacts stderr`() {
    val cli = script("echo 'token=supersecret' >&2; sleep 30")
    val failure = assertFailsWith<MedusaException> {
      AndroidCliBackend(dir, "sample", cli, reliability = ReliabilityOptions(discoveryTimeoutSeconds = 1)).list()
    }
    assertTrue("REDACTED" in failure.message.orEmpty())
    assertFalse("supersecret" in failure.message.orEmpty())
  }

  @Test fun `a streamed image absent from the final manifest fails`() {
    val cli = script("""
      mkdir -p "$out/dev/app/ButtonKt"
      cp "§here/img.png" "$out/dev/app/ButtonKt/APreview_0.png"
      sleep 0.1
      printf '%s\n' '{' '"success":true,"results":[]}'
    """.trimIndent().replace('§', '$'))
    assertFailsWith<MedusaException> { runBlocking { backend(cli).render(listOf(fqnA), out).toList() } }
  }

}
