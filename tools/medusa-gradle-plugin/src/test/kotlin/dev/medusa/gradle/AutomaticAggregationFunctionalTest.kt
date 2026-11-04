package dev.medusa.gradle

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.gradle.testkit.runner.GradleRunner

class AutomaticAggregationFunctionalTest {
  private val root = createTempDirectory("medusa-auto").toFile()
  private fun write(path: String, value: String) = File(root, path).also { it.parentFile.mkdirs(); it.writeText(value) }
  init {
    write("settings.gradle", "rootProject.name='automatic'\ninclude ':app', ':lib'\n")
    write("build.gradle", "")
    write("gradle.properties", "medusa.differ=offbytwo\nmedusa.autoInstall=false\n")
    write("buildSrc/build.gradle", """
      plugins { id 'java-gradle-plugin' }
      gradlePlugin { plugins {
        app { id = 'com.android.application'; implementationClass = 'FakeApp' }
        lib { id = 'com.android.library'; implementationClass = 'FakeLib' }
      } }
    """.trimIndent())
    write("buildSrc/src/main/java/FakeAndroid.java", """
      import org.gradle.api.*;
      public class FakeAndroid implements Plugin<Project> {
        public void apply(Project p) {
          p.getPluginManager().apply("base");
          p.getConfigurations().maybeCreate("default").setCanBeResolved(false);
          p.getConfigurations().create("debugRuntimeClasspath").setCanBeConsumed(false);
        }
      }
    """.trimIndent())
    write("buildSrc/src/main/java/FakeApp.java", "public class FakeApp extends FakeAndroid {}")
    write("buildSrc/src/main/java/FakeLib.java", "public class FakeLib extends FakeAndroid {}")
    for (module in listOf("app", "lib")) {
      write("$module/build.gradle", """
        plugins { id 'com.android.${if (module == "app") "application" else "library"}'; id 'io.github.araujojordan.medusa' }
        medusa { androidCliPath = '${File(root, "android").path}' }
        ${if (module == "app") "dependencies { debugRuntimeClasspath project(':lib') }" else ""}
      """.trimIndent())
      write("$module/src/main/kotlin/fx/$module/Screen.kt", "package fx.$module\nfun ${module}Preview() {}")
    }
    ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", File(root, "image.png"))
    write("android", """
      #!/bin/bash
      here="§(cd "§(dirname "§0")" && pwd)"
      echo "§*" >> "§here/calls.log"
      ref="§{@: -1}"
      all=0
      names=()
      for a in "§@"; do case "§a" in --all) all=1;; --fqn=*) names+=("§{a#--fqn=}");; --output=*) out="§{a#--output=}";; esac; done
      echo '{'
      if [ "§3" = list ]; then
        echo '"success":true,"previews":['
        if [ "§ref" = :app ]; then
          echo '{"methodFqn":"fx.app.ScreenKt.appPreview"}'
          if [ §all = 1 ]; then echo ',{"methodFqn":"fx.lib.ScreenKt.libPreview"}'; fi
        else echo '{"methodFqn":"fx.lib.ScreenKt.libPreview"}'; fi
      else
        echo '"success":true,"results":['
        sep=''
        for name in "§{names[@]}"; do
          module=lib; if [[ "§name" == fx.app.* ]]; then module=app; fi
          dir="§out/fx/§module/ScreenKt"; mkdir -p "§dir"
          image="§dir/§{module}Preview_0.png"; cp "§here/image.png" "§image"
          echo "§sep{\"previewId\":\"§name\",\"methodFqn\":\"§name\",\"imagePath\":\"§image\"}"
          sep=','
        done
      fi
      echo ']}'
    """.trimIndent().replace('§', '$')).setExecutable(true)
  }
  private fun run(vararg args: String) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
    .withArguments(*args, "--stacktrace").build()
  private fun calls() = File(root, "calls.log").readLines()

  @Test fun `root groups once and keeps module reports with configuration cache`() {
    val recorded = run("medusaRecord", "--configuration-cache", "--info")
    assertEquals(1, calls().count { "preview render" in it }, recorded.output)
    assertTrue(calls().any { "--all" in it }, recorded.output)
    assertTrue(File(root, "lib/src/main/kotlin/fx/lib/Screen_libPreview.png").isFile)
    run("medusaVerify", "--configuration-cache")
    val second = run("medusaVerify", "--configuration-cache")
    assertTrue("Reusing configuration cache" in second.output)
    for (module in listOf("app", "lib")) {
      val xml = File(root, "$module/build/medusa/test-results/medusa.xml").readText()
      assertTrue("${module}Preview" in xml)
      assertFalse("${if (module == "app") "lib" else "app"}Preview" in xml)
    }
  }

  @Test fun `check deduplicates and qualified tasks stay standalone`() {
    run("medusaRecord")
    File(root, "calls.log").writeText("")
    run("check")
    assertEquals(1, calls().count { "preview render" in it })
    File(root, "calls.log").writeText("")
    run(":lib:medusaVerify")
    assertEquals(1, calls().count { "preview render" in it })
    assertTrue(calls().all { it.endsWith(":lib") })
  }

  @Test fun `opt out and filters retain coverage`() {
    run("medusaRecord", "-Pmedusa.autoAggregate=false")
    assertEquals(2, calls().count { "preview render" in it })
    File(root, "calls.log").writeText("")
    run("medusaVerify", "--preview=libPreview")
    val render = calls().single { "preview render" in it }
    assertTrue("libPreview" in render)
    assertFalse("appPreview" in render)
  }
  @Test fun `group failures belong to their source module`() {
    run("medusaRecord")
    val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until 8) for (y in 0 until 8) image.setRGB(x, y, 0xFFFF0000.toInt())
    ImageIO.write(image, "png", File(root, "image.png"))
    GradleRunner.create().withProjectDir(root).withPluginClasspath()
      .withArguments("medusaVerify", "--stacktrace").buildAndFail()
    for (module in listOf("app", "lib")) {
      val failures = File(root, "$module/build/medusa/failures").listFiles().orEmpty()
      assertEquals(2, failures.size)
      assertTrue(failures.all { "${module}Preview" in it.name })
      val xml = File(root, "$module/build/medusa/test-results/medusa.xml").readText()
      assertTrue("failures=\"1\"" in xml)
    }
  }

  @Test fun `incompatible settings and check opt out stay separate`() {
    File(root, "lib/build.gradle").appendText("\nmedusa { compareParallelism = 1; verifyOnCheck = false }\n")
    run("medusaRecord")
    assertEquals(2, calls().count { "preview render" in it })
    File(root, "calls.log").writeText("")
    run("check")
    assertEquals(1, calls().count { "preview render" in it })
    assertTrue(calls().all { it.endsWith(":app") })
  }

  @Test fun `shared dependency is never automatically hosted`() {
    File(root, "settings.gradle").appendText("\ninclude ':other'\n")
    write("other/build.gradle", """
      plugins { id 'com.android.application'; id 'io.github.araujojordan.medusa' }
      medusa { androidCliPath = '${File(root, "android").path}' }
      dependencies { debugRuntimeClasspath project(':lib') }
    """.trimIndent())
    run("medusaList")
    assertEquals(3, calls().count { "preview list" in it })
    assertFalse(calls().any { "--all" in it })
  }

  @Test fun `ambiguous ownership falls back before recording`() {
    File(root, "lib/src/main/kotlin/fx/lib/Screen.kt").appendText("\nfun appPreview() {}\n")
    val result = run("medusaRecord")
    assertTrue("ambiguous source ownership" in result.output)
    assertEquals(2, calls().count { "preview render" in it })
    assertTrue(File(root, "app/src/main/kotlin/fx/app/Screen_appPreview.png").isFile)
    assertTrue(File(root, "lib/src/main/kotlin/fx/lib/Screen_libPreview.png").isFile)
  }

  @Test fun `CLI preparation does not configure a second aggregation plan`() {
    File(root, "app/build.gradle").appendText("\ntasks.register('generateDebugComposePreviewRunfiles')\n")
    File(root, "build.gradle").appendText("\ngradle.projectsEvaluated { assert tasks.findByName('medusaRecord') == null }\n")
    run(":app:generateDebugComposePreviewRunfiles")
    assertFalse(File(root, "calls.log").exists())
  }

}
