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
 * A build with `:feature:home` and `:lib` (whose directory is remapped to `old/lib`, so the directory and the Gradle path
 * differ), driven through the real plugin with a fake `android` that answers differently per module reference.
 */
class MedusaMultiModuleFunctionalTest {
  private val project = createTempDirectory("medusa-multimodule").toFile()
  private val homeGolden = File(project, "feature/home/src/main/kotlin/fx/home/Home_HomePreview.png")
  private val libGolden = File(project, "old/lib/src/main/kotlin/fx/lib/Lib_LibPreview.png")

  init {
    write("gradle.properties", "medusa.differ=offbytwo\nmedusa.autoInstall=false\n") // hermetic, see MedusaPluginFunctionalTest
    write(
      "settings.gradle.kts",
      """
      rootProject.name = "fx"
      include(":feature:home", ":lib")
      project(":lib").projectDir = file("old/lib")
      """.trimIndent(),
    )
    for ((dir, extra) in listOf("feature/home" to "includeDependentModules = providers.gradleProperty(\"agg\").isPresent", "old/lib" to "")) {
      write(
        "$dir/build.gradle.kts",
        """
        plugins {
          id("com.android.library")
          id("io.github.araujojordan.medusa")
        }
        medusa {
          androidCliPath = "${File(project, "android").absolutePath}"
          $extra
        }
        """.trimIndent(),
      )
    }
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
    write("feature/home/src/main/kotlin/fx/home/Home.kt", "package fx.home\nfun HomePreview() {}\n")
    write("old/lib/src/main/kotlin/fx/lib/Lib.kt", "package fx.lib\nfun LibPreview() {}\n")
    val img = BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 6) for (x in 0 until 6) img.setRGB(x, y, 0xFF336699.toInt())
    ImageIO.write(img, "png", File(project, "current.png"))
    writeCli()
  }

  private fun write(path: String, text: String) = File(project, path).also {
    it.parentFile.mkdirs()
    it.writeText(text)
  }

  /** `:lib` lists LibPreview, any other module lists HomePreview, and `--all` adds LibPreview. The last argument is the module reference. */
  private fun writeCli() {
    val script = """
      #!/bin/bash
      here="§(cd "§(dirname "§0")" && pwd)"
      echo "§*" >> "§here/calls.log"
      ref="§{@: -1}"
      all=""
      out=""
      names=()
      for a in "§@"; do
        case "§a" in
          --all) all=1 ;;
          --output=*) out="§{a#--output=}" ;;
          --fqn=*) names+=("§{a#--fqn=}") ;;
        esac
      done
      if [ "§3" = list ]; then
        echo '{'; echo '  "success": true,'; echo '  "previews": ['
        sep=""
        if [ "§ref" != ":lib" ]; then printf '%s    { "methodFqn": "fx.home.HomeKt.HomePreview", "previewParams": {} }' "§sep"; sep=§',\n'; fi
        if [ "§ref" = ":lib" ] || [ -n "§all" ]; then printf '%s    { "methodFqn": "fx.lib.LibKt.LibPreview", "previewParams": {} }' "§sep"; fi
        echo; echo '  ]'; echo '}'
        exit 0
      fi
      echo '{'; echo '  "success": true,'; echo '  "results": ['
      sep=""
      for fqn in "§{names[@]}"; do
        name="§{fqn##*.}"
        cls="§{fqn%.*}"
        dir="§out/§(echo "§cls" | tr . /)"
        mkdir -p "§dir"
        cp "§here/current.png" "§dir/§{name}_0.png"
        printf '%s    { "previewId": "%s", "methodFqn": "%s", "imagePath": "%s" }' "§sep" "§name" "§fqn" "§dir/§{name}_0.png"
        sep=§',\n'
      done
      echo; echo '  ]'; echo '}'
    """.trimIndent().replace('§', '$') + "\n"
    write("android", script).setExecutable(true)
  }

  private fun gradle(vararg args: String) =
    GradleRunner.create().withProjectDir(project).withPluginClasspath().withArguments(*args, "--stacktrace")

  private fun calls(): List<String> = File(project, "calls.log").takeIf { it.exists() }?.readLines().orEmpty()

  @Test
  fun `the CLI gets the Gradle path of each module, not its directory`() {
    gradle("medusaList").build()

    val listCalls = calls().filter { "preview list" in it }
    assertTrue(listCalls.any { it.endsWith(" :feature:home") }, listCalls.toString())
    assertTrue(listCalls.any { it.endsWith(" :lib") }, "the remapped directory old/lib must not be used: $listCalls")
    assertFalse(listCalls.any { "old/lib" in it || "feature/home" in it }, listCalls.toString())
  }

  @Test
  fun `record and verify from the root work in every module and put goldens next to each module's sources`() {
    gradle("medusaRecord").build()

    assertTrue(homeGolden.isFile, "golden of :feature:home")
    assertTrue(libGolden.isFile, "golden of :lib in its remapped directory")
    val verify = gradle("medusaVerify").build()
    assertEquals(2, Regex("1 preview\\(s\\) match").findAll(verify.output).count(), verify.output)
  }

  @Test
  fun `the number of modules that run the CLI at once follows the machine and can be overridden`() {
    val cores = Runtime.getRuntime().availableProcessors()

    val computed = gradle("medusaList", "--info").build().output
    assertTrue("modules start the Android CLI at once ($cores logical cores, " in computed, computed)
    assertTrue("GB, nested builds with a" in computed, computed)
    assertTrue("7 modules start the Android CLI at once (medusa.maxConcurrentRenders)" in gradle("medusaList", "--info", "-Pmedusa.maxConcurrentRenders=7").build().output)
  }

  @Test
  fun `medusaSetup shows the concurrency it chose`() {
    val out = gradle("medusaSetup", "-Pmedusa.differ=offbytwo").build().output

    assertTrue(Regex("Concurrency: \\d+ modules start the Android CLI at once \\(").containsMatchIn(out), out)
  }

  @Test
  fun `modules can run at the same time under --parallel`() {
    gradle("medusaRecord", "--parallel").build()

    assertTrue(homeGolden.isFile && libGolden.isFile)
  }

  @Test
  fun `an unqualified task skips modules where the filter matches nothing and says so`() {
    val result = gradle("medusaRecord", "--preview=HomePreview").build()

    assertTrue(homeGolden.isFile)
    assertFalse(libGolden.exists())
    assertTrue(":lib:medusaRecord: Skipped" in result.output, result.output)
  }

  @Test
  fun `a task qualified with a module path still fails when the filter matches nothing`() {
    val result = gradle(":lib:medusaRecord", "--preview=HomePreview").buildAndFail()

    assertTrue("No preview matches the --preview filter" in result.output, result.output)
  }

  @Test
  fun `including dependent modules lists with --all and finds their sources in the other module`() {
    val result = gradle(":feature:home:medusaRecord", "-Pagg").build()

    assertTrue(calls().any { "preview list" in it && "--all" in it }, calls().toString())
    assertTrue(homeGolden.isFile, "own preview")
    assertTrue(libGolden.isFile, "the dependent module's golden goes next to ITS source, in the remapped directory")
    assertTrue("recorded 2 preview(s)" in result.output, result.output)
  }

  @Test
  fun `without including dependent modules only the own previews are handled`() {
    gradle(":feature:home:medusaRecord").build()

    assertTrue(homeGolden.isFile)
    assertFalse(libGolden.exists())
    assertFalse(calls().any { "--all" in it })
  }
}
