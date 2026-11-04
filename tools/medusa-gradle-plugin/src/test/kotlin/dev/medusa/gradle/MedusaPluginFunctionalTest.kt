package dev.medusa.gradle

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

/**
 * Drives the real plugin through Gradle TestKit. The Android CLI is replaced by a shell script that
 * lists two previews and "renders" whatever `current.png` holds, so no Android SDK is needed.
 */
class MedusaPluginFunctionalTest {
  private val project = createTempDirectory("medusa-fixture").toFile()
  private val sourceDir = File(project, "src/main/kotlin/dev/app")
  private val goldenPrimary = File(sourceDir, "Button_PrimaryPreview.png")
  private val goldenDark = File(sourceDir, "Button_DarkPreview.png")

  init {
    // Hermetic: never depend on, or download, a tool that happens to be (not) installed on the machine running the tests.
    write("gradle.properties", "medusa.differ=offbytwo\nmedusa.autoInstall=false\n")
    write("settings.gradle.kts", """rootProject.name = "fixture"""")
    write(
      "build.gradle.kts",
      """
      plugins {
        id("com.android.library")
        id("io.github.araujojordan.medusa")
      }
      medusa {
        androidCliPath = "${File(project, "android").absolutePath}"
        accessibilitySnapshots = providers.gradleProperty("a11y").isPresent
        providers.gradleProperty("extra").orNull?.let { sourceDirs.from(it) }
        if (providers.gradleProperty("dsl").isPresent) {
          differ = "yiq"
          yiq { threshold = 0.0 }
        }
      }
      """.trimIndent(),
    )
    // Stands in for AGP: registers the plugin id Medusa waits for and provides `check`.
    write(
      "buildSrc/build.gradle.kts",
      """
      plugins { `java-gradle-plugin` }
      gradlePlugin { plugins { create("fakeAgp") { id = "com.android.library"; implementationClass = "FakeAgp" } } }
      """.trimIndent(),
    )
    write(
      "buildSrc/src/main/java/FakeAgp.java",
      """
      import org.gradle.api.Plugin;
      import org.gradle.api.Project;
      public class FakeAgp implements Plugin<Project> {
        public void apply(Project p) { p.getPluginManager().apply("base"); }
      }
      """.trimIndent(),
    )
    write("src/main/kotlin/dev/app/Button.kt", "package dev.app\nfun PrimaryPreview() {}\nfun DarkPreview() {}\n")
    writeCli()
    setRendering(0xFF336699.toInt())
    setTree("Primary")
  }

  private fun write(path: String, text: String) = File(project, path).also {
    it.parentFile.mkdirs()
    it.writeText(text)
  }

  private fun setRendering(argb: Int) {
    val img = BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 6) for (x in 0 until 6) img.setRGB(x, y, argb)
    ImageIO.write(img, "png", File(project, "current.png"))
  }

  private fun setTree(text: String) {
    write(
      "tree.json",
      """{ "components": [ { "name": "Box", "bounds": { "left": 1, "top": 2, "right": 3, "bottom": 4 },
        "semantics": { "role": "Button" }, "children": [ { "name": "Text", "semantics": { "text": "$text" } } ] } ] }""",
    )
  }

  private fun writeCli() {
    val cli = write(
      "android",
      """
      #!/bin/bash
      if [ "${'$'}1" = -V ] || [ "${'$'}2" = -V ]; then echo "fake-cli 1.0"; exit 0; fi
      here="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
      echo "${'$'}*" >> "${'$'}here/calls.log"
      if [ "${'$'}3" = list ]; then
      cat <<'JSON'
      Build log: fake
      {
        "success": true,
        "previews": [
          { "methodFqn": "dev.app.ButtonKt.PrimaryPreview", "previewParams": {} },
          { "methodFqn": "dev.app.ButtonKt.DarkPreview", "previewParams": {} }
        ]
      }
      JSON
      exit 0
      fi
      out=""
      names=()
      tree=""
      for a in "${'$'}@"; do
        case "${'$'}a" in
          --component-tree) tree=1 ;;
          --output=*) out="${'$'}{a#--output=}" ;;
          --fqn=*) names+=("${'$'}{a#--fqn=}") ;;
        esac
      done
      echo "{"
      echo '  "success": true,'
      echo '  "results": ['
      sep=""
      for fqn in "${'$'}{names[@]}"; do
        name="${'$'}{fqn##*.}"
        dir="${'$'}out/dev/app/ButtonKt"
        mkdir -p "${'$'}dir"
        cp "${'$'}here/current.png" "${'$'}dir/${'$'}{name}_0.png"
        if [ -n "${'$'}tree" ]; then cp "${'$'}here/tree.json" "${'$'}dir/${'$'}{name}_0.json"; fi
        printf '%s    { "previewId": "%s", "methodFqn": "%s", "imagePath": "%s" }' "${'$'}sep" "${'$'}name" "${'$'}fqn" "${'$'}dir/${'$'}{name}_0.png"
        sep=$',\n'
      done
      echo
      echo '  ]'
      echo "}"
      """.trimIndent() + "\n",
    )
    cli.setExecutable(true)
  }

  private fun gradle(vararg args: String) =
    GradleRunner.create()
      .withProjectDir(project)
      .withPluginClasspath()
      .withArguments(*args, "--stacktrace")

  private fun renderedFqns(): List<String> =
    File(project, "calls.log").readLines().filter { "preview render" in it }
      .flatMap { line -> line.split(' ').filter { it.startsWith("--fqn=") }.map { it.removePrefix("--fqn=") } }

  @Test
  fun `record writes goldens next to sources and verify passes`() {
    val record = gradle("medusaRecord").build()

    assertTrue(goldenPrimary.isFile && goldenDark.isFile)
    assertTrue("recorded 2 preview(s), 2 written" in record.output, record.output)
    assertTrue("2 preview(s) match" in gradle("medusaVerify").build().output)
  }

  @Test
  fun `verify fails with a report when the rendering changes`() {
    gradle("medusaRecord").build()
    setRendering(0xFFCC0000.toInt())

    val result = gradle("medusaVerify").buildAndFail()

    assertTrue("2 of 2 preview(s) failed" in result.output, result.output)
    assertTrue(File(project, "build/medusa/report/index.html").isFile)
    assertTrue(File(project, "build/medusa/failures").walkTopDown().first { it.name == "delta-dev.app.ButtonKt.PrimaryPreview.png" }.isFile)
    assertTrue(File(project, "build/medusa/failures").walkTopDown().first { it.name == "dev.app.ButtonKt.PrimaryPreview_actual.png" }.isFile)
  }

  @Test
  fun `verify without goldens tells the user to record`() {
    val result = gradle("medusaVerify").buildAndFail()

    assertTrue("Run medusaRecord" in result.output, result.output)
  }

  @Test
  fun `preview option limits record to one preview`() {
    gradle("medusaRecord", "--preview=PrimaryPreview").build()

    assertTrue(goldenPrimary.isFile)
    assertFalse(goldenDark.exists())
    assertEquals(listOf("dev.app.ButtonKt.PrimaryPreview"), renderedFqns())
  }

  @Test
  fun `preview option can be repeated and takes globs`() {
    gradle("medusaRecord", "--preview=Primary*", "--preview=DarkPreview").build()

    assertTrue(goldenPrimary.isFile && goldenDark.isFile)
  }

  @Test
  fun `delete and cleanRecord`() {
    gradle("medusaRecord").build()

    gradle("medusaDelete", "--preview=DarkPreview").build()
    assertTrue(goldenPrimary.isFile)
    assertFalse(goldenDark.exists())

    val clean = gradle("medusaCleanRecord").build()
    assertTrue("Re-recorded 2 golden image(s)" in clean.output, clean.output)
    assertTrue(goldenPrimary.isFile && goldenDark.isFile)
  }

  @Test
  fun `list shows previews and golden locations without rendering`() {
    val result = gradle("medusaList").build()

    assertTrue("dev.app.ButtonKt.PrimaryPreview" in result.output)
    assertTrue("Button_PrimaryPreview.png" in result.output)
    assertTrue(renderedFqns().isEmpty())
  }

  @Test
  fun `check runs verify`() {
    val result = gradle("check").buildAndFail()

    assertTrue("medusaVerify" in result.output, result.output)
  }

  @Test
  fun `differ and tolerance can be chosen with gradle properties`() {
    gradle("medusaRecord").build()
    setRendering(0xFF336699.toInt() + 1) // blue +1 on every pixel

    gradle("medusaVerify").build() // Paparazzi's default differ (offbytwo) calls this similar

    val strict = gradle("medusaVerify", "-Pmedusa.differ=pixelperfect", "-Pmedusa.maxPercentDifference=0").buildAndFail()
    assertTrue("2 of 2 preview(s) failed" in strict.output, strict.output)
    gradle("medusaVerify", "-Pmedusa.differ=pixelperfect", "-Pmedusa.maxPercentDifference=1").build()
  }

  @Test
  fun `unknown differ lists the valid ones`() {
    val result = gradle("medusaList", "-Pmedusa.differ=bogus").buildAndFail()

    assertTrue("Unknown differ 'bogus'" in result.output && "pixelperfect" in result.output, result.output)
  }

  @Test
  fun `accessibility snapshots are recorded and verified`() {
    gradle("medusaRecord", "-Pa11y").build()
    val semantics = File(sourceDir, "Button_PrimaryPreview.semantics.txt")
    assertTrue(semantics.isFile && "Text text=\"Primary\"" in semantics.readText(), "snapshot missing")
    gradle("medusaVerify", "-Pa11y").build()

    setTree("Renamed")
    val result = gradle("medusaVerify", "-Pa11y").buildAndFail()

    assertTrue("accessibility tree changed" in result.output, result.output)
    assertTrue(File(project, "build/medusa/failures").walkTopDown().first { it.name == "dev.app.ButtonKt.PrimaryPreview.semantics.actual.txt" }.isFile)
  }

  @Test
  fun `extra source directories are searched`() {
    File(project, "src").deleteRecursively()
    write("custom/dev/app/Button.kt", "package dev.app\nfun PrimaryPreview() {}\nfun DarkPreview() {}\n")

    gradle("medusaRecord", "-Pextra=custom").build()

    assertTrue(File(project, "custom/dev/app/Button_PrimaryPreview.png").isFile)
  }

  private fun installed(tool: String) =
    System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { File(it, tool).canExecute() }

  /** Records with the default differ so the goldens exist, then checks one differ against two renderings. */
  private fun assertDifferJudges(differ: String, vararg extra: String, similar: Int, different: Int) {
    gradle("medusaRecord").build()
    setRendering(similar)
    gradle("medusaVerify", "-Pmedusa.differ=$differ", *extra).build()

    setRendering(different)
    val result = gradle("medusaVerify", "-Pmedusa.differ=$differ", *extra).buildAndFail()

    assertTrue("2 of 2 preview(s) failed" in result.output, result.output)
    val delta = ImageIO.read(File(project, "build/medusa/failures").walkTopDown().first { it.name == "delta-dev.app.ButtonKt.PrimaryPreview.png" })
    assertEquals(18, delta.width, "expected | delta | actual of 6px wide images, whatever differ drew the middle")
  }

  @Test
  fun `yiq differ runs in process`() {
    assertDifferJudges("yiq", similar = 0xFF336699.toInt(), different = 0xFFCC0000.toInt())
  }

  @Test
  fun `odiff runs the real binary through the server pool`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(installed("odiff"), "odiff is not installed")
    assertDifferJudges("odiff", similar = 0xFF336699.toInt(), different = 0xFFCC0000.toInt())
  }

  @Test
  fun `magick runs the real binary`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(installed("magick"), "ImageMagick is not installed")
    assertDifferJudges("magick", similar = 0xFF336699.toInt(), different = 0xFFCC0000.toInt())
  }

  @Test
  fun `differ options are passed on, a small drift is only caught with threshold zero`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(installed("odiff"), "odiff is not installed")
    gradle("medusaRecord").build()
    setRendering(0xFF346699.toInt()) // one blue-ish level of difference in one channel

    gradle("medusaVerify", "-Pmedusa.differ=odiff").build() // default threshold 0.1 treats it as equal
    val strict = gradle("medusaVerify", "-Pmedusa.differ=odiff", "-Pmedusa.odiff.threshold=0").buildAndFail()

    assertTrue("odiff:" in strict.output, strict.output)
  }

  @Test
  fun `the Kotlin DSL block selects the differ and its options`() {
    gradle("medusaRecord").build()
    setRendering(0xFF346699.toInt())

    gradle("medusaVerify").build() // default differ: off by one is similar
    val result = gradle("medusaVerify", "-Pdsl").buildAndFail() // yiq { threshold = 0.0 }: any change counts

    assertTrue("2 of 2 preview(s) failed" in result.output, result.output)
  }

  @Test
  fun `selecting a tool that is not installed fails with the path that was configured`() {
    val result = gradle("medusaVerify", "-Pmedusa.differ=odiff", "-Pmedusa.odiff.binary=/nonexistent/odiff").buildAndFail()

    assertTrue("not found at the configured path: /nonexistent/odiff" in result.output, result.output)
  }

  @Test
  fun `an unknown differ lists all of them`() {
    val result = gradle("medusaList", "-Pmedusa.differ=bogus").buildAndFail()

    assertTrue("yiq" in result.output && "odiff" in result.output && "magick" in result.output && "pixelperfect" in result.output, result.output)
  }

  @Test
  fun `odiff is the default differ`() {
    File(project, "gradle.properties").delete() // the fixture pins offbytwo; remove that to see the real default

    // Only the odiff comparer looks at this option, so the failure proves odiff was selected.
    val result = gradle("medusaVerify", "-Pmedusa.odiff.binary=/nonexistent/odiff").buildAndFail()

    assertTrue("not found at the configured path: /nonexistent/odiff" in result.output, result.output)
  }

  @Test
  fun `medusaSetup reports the Android CLI and a built in differ without installing anything`() {
    val result = gradle("medusaSetup").build()

    assertTrue("Android CLI: ${File(project, "android").absolutePath}" in result.output, result.output)
    assertTrue("Differ 'offbytwo' is built in, nothing to install." in result.output, result.output)
  }

  @Test
  fun `medusaSetup finds the odiff that is installed`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(installed("odiff"), "odiff is not installed")

    val result = gradle("medusaSetup", "-Pmedusa.differ=odiff").build()

    assertTrue(Regex("odiff: /.*odiff").containsMatchIn(result.output), result.output)
  }

  @Test
  fun `medusaSetup fails with a clear message for a configured binary that does not exist`() {
    val result = gradle("medusaSetup", "-Pmedusa.differ=odiff", "-Pmedusa.odiff.binary=/nonexistent/odiff").buildAndFail()

    assertTrue("not found at the configured path" in result.output, result.output)
  }

  @Test
  fun `missing Android CLI gives an actionable error`() {
    File(project, "android").delete()

    val result = gradle("medusaList").buildAndFail()

    assertTrue("Android CLI not found at the configured androidCliPath" in result.output, result.output)
  }
  @Test fun `diagnosis repeats without modifying goldens and supports configuration cache`() {
    gradle("medusaRecord").build()
    val before = goldenPrimary.readBytes()
    val result = gradle("medusaDiagnose", "--preview=PrimaryPreview", "--configuration-cache").build()
    assertTrue("stable-match" in result.output, result.output)
    assertEquals(11, File(project, "calls.log").readLines().count { "preview render" in it })
    assertTrue(before.contentEquals(goldenPrimary.readBytes()))
    val second = gradle("medusaDiagnose", "--preview=PrimaryPreview", "--configuration-cache").build()
    assertTrue("Reusing configuration cache" in second.output)
  }

  @Test fun `invalid recovered pass policy fails before rendering`() {
    val result = gradle("medusaVerify", "-Pmedusa.allowRecoveredPass=true").buildAndFail()
    assertTrue("requires visualRetryCount" in result.output)
    assertFalse(File(project, "calls.log").exists())
  }

}
