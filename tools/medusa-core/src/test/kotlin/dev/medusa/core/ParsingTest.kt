package dev.medusa.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
  private fun fixture(name: String) = checkNotNull(javaClass.getResource("/$name")) { name }.readText()

  @Test
  fun `parses preview list output that has progress lines before the JSON`() {
    val result = parseListResult(fixture("preview-list.stdout.txt"))

    assertTrue(result.success)
    assertEquals(5, result.previews.size)
    val labels = result.previews.single { it.name == "PrimaryButtonLabelsPreview" }
    assertEquals("dev.medusa.sample.ui.components.ButtonKt", labels.className)
    assertTrue(labels.hasParameterProvider)
    assertEquals("32", result.previews.single { it.name == "PrimaryButtonDarkPreview" }.previewParams["uiMode"])
  }

  @Test
  fun `parses render output with one result per parameter value`() {
    val result = parseRenderResult(fixture("preview-render.stdout.txt"))

    assertTrue(result.success)
    assertEquals(6, result.results.size)
    val labels = result.results.filter { it.previewId == "PrimaryButtonLabelsPreview" }
    assertEquals(listOf("_0.png", "_1.png"), labels.map { it.imagePath!!.takeLast(6) })
    assertNotNull(result.results.first().componentTreePath)
  }

  private val components = "dev.medusa.sample.ui.components.CustomAnnotationsKt"

  @Test
  fun `custom and nested multipreview annotations list one entry per expanded preview`() {
    val byFqn = parseListResult(fixture("multipreview-list.stdout.txt")).previews.groupBy { it.methodFqn }
    fun entries(name: String) = byFqn.getValue("$components.$name")

    assertEquals(3, entries("WidthsPreview").size, "@WidthPreviews: three @Preview")
    assertEquals(3, entries("ThemesPreview").size, "@PreviewLightDark (2) + one own @Preview, wrapped in a custom annotation")
    assertEquals(4, entries("NestedAnnotationsPreview").size, "a custom annotation that nests another one and adds a @Preview")
    assertEquals(setOf("compact", "medium", "expanded", "german"), entries("NestedAnnotationsPreview").map { it.previewParams["name"] }.toSet())
    assertTrue(entries("WidthsLabelsPreview").all { it.hasParameterProvider }, "every entry carries the @PreviewParameter provider")
    assertEquals(3, entries("WidthsLabelsPreview").size)
  }

  @Test
  fun `a custom multipreview with a parameter provider renders entries times values images with consecutive indices`() {
    val results = parseRenderResult(fixture("multipreview-render.stdout.txt")).results.groupBy { it.previewId }

    assertEquals(4, results.getValue("NestedAnnotationsPreview").size)
    assertEquals(3, results.getValue("ThemesPreview").size)
    assertEquals(3, results.getValue("WidthsPreview").size)
    val labels = results.getValue("WidthsLabelsPreview")
    assertEquals(3 * 2, labels.size, "3 annotation entries x 2 provider values")
    assertEquals((0..5).toList(), labels.map { it.index }.sortedBy { it })
  }

  @Test
  fun `render errors are surfaced per preview`() {
    val out = """
      noise
      {"success": false, "results": [{"previewId": "A", "methodFqn": "x.AKt.A",
        "error": {"status": "VALIDATION_ERROR", "message": "boom"}}]}
    """.trimIndent()
    // single-line JSON is not what the CLI prints, so make it multi-line like the real thing
    val multiline = out.replace("{\"success\"", "{\n\"success\"")
    val result = parseRenderResult(multiline)

    assertNull(result.results.single().imagePath)
    assertEquals("boom", result.results.single().error?.message)
  }

  @Test
  fun `output without JSON is an error that includes the output`() {
    val e = assertFailsWith<MedusaException> { parseListResult("Build failed\nsomething") }
    assertTrue("Build failed" in e.message!!)
  }
}
