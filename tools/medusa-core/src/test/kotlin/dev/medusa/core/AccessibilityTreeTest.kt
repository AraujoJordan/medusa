package dev.medusa.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessibilityTreeTest {
  private val real = checkNotNull(javaClass.getResource("/component-tree.json")).readText()

  @Test
  fun `normalizes a real component tree from the Android CLI`() {
    val lines = AccessibilityTree.normalize(real).lines().filter { it.isNotEmpty() }

    assertEquals(AccessibilityTree.HEADER, lines[0])
    assertEquals(5, lines.size, lines.toString())
    assertTrue(Regex("""Box actions="OnClick" role="Button" \[\d+,\d+]\[\d+,\d+]""").matches(lines[1]), lines[1])
    assertTrue(Regex("""  Text text="Primary" \[\d+,\d+]\[\d+,\d+]""").matches(lines[2]), lines[2])
    assertTrue(lines[3].startsWith("Box ") && lines[4].startsWith("  Text text=\"Secondary\""), lines.toString())
  }

  @Test
  fun `components without semantics are skipped but their children are kept`() {
    val json = """{ "components": [ { "name": "Column", "semantics": {}, "children": [
      { "name": "Row", "children": [ { "name": "Text", "semantics": { "text": "a \"b\"" } } ] } ] } ] }"""

    val text = AccessibilityTree.normalize(json)

    assertEquals("${AccessibilityTree.HEADER}\nText text=\"a \\\"b\\\"\"\n", text)
  }

  @Test
  fun `output is stable regardless of property order`() {
    val a = """{ "components": [ { "name": "X", "semantics": { "role": "Button", "actions": "OnClick" } } ] }"""
    val b = """{ "components": [ { "name": "X", "semantics": { "actions": "OnClick", "role": "Button" } } ] }"""

    assertEquals(AccessibilityTree.normalize(a), AccessibilityTree.normalize(b))
  }

  @Test
  fun `sibling order does not matter, the CLI emits it in a different order from run to run`() {
    fun node(text: String, top: Int, left: Int = 0) =
      """{ "name": "Text", "bounds": { "left": $left, "top": $top, "right": ${left + 10}, "bottom": ${top + 10} },
         "semantics": { "text": "$text" } }"""
    // Seen on the real Scaffold preview: the app bar title was listed first in one run and last in the next.
    val a = """{ "components": [ { "name": "Scaffold", "children": [ ${node("title", 47)}, ${node("hello", 210)}, ${node("ok", 396)} ] } ] }"""
    val b = """{ "components": [ { "name": "Scaffold", "children": [ ${node("hello", 210)}, ${node("ok", 396)}, ${node("title", 47)} ] } ] }"""

    assertEquals(AccessibilityTree.normalize(a), AccessibilityTree.normalize(b))
    assertEquals(
      listOf("title", "hello", "ok"),
      Regex("""text="(\w+)"""").findAll(AccessibilityTree.normalize(a)).map { it.groupValues[1] }.toList(),
      "reading order: top to bottom",
    )
  }

  @Test
  fun `children stay under their semantic parent when siblings are sorted`() {
    fun leaf(text: String, top: Int) =
      """{ "name": "Text", "bounds": { "left": 0, "top": $top, "right": 1, "bottom": 1 }, "semantics": { "text": "$text" } }"""
    fun button(top: Int, label: String) =
      """{ "name": "Box", "bounds": { "left": 0, "top": $top, "right": 1, "bottom": 1 }, "semantics": { "role": "Button" },
         "children": [ ${leaf(label, top + 1)} ] }"""
    val tree = """{ "components": [ ${button(200, "second")}, ${button(100, "first")} ] }"""

    val lines = AccessibilityTree.normalize(tree).lines().filter { it.isNotEmpty() }.drop(1)

    assertEquals(4, lines.size, lines.toString())
    assertTrue(lines[0].startsWith("Box ") && lines[2].startsWith("Box "), "buttons at depth 0: $lines")
    assertTrue(lines[1].startsWith("  Text ") && lines[3].startsWith("  Text "), "labels stay under their button: $lines")
    assertTrue("first" in lines[1] && "second" in lines[3], lines.toString())
  }

  @Test
  fun `invalid json is a clear error`() {
    assertFailsWith<MedusaException> { AccessibilityTree.normalize("not json") }
  }

  @Test
  fun `first difference points at the line`() {
    assertNull(AccessibilityTree.firstDifference("a\nb\n", "a\nb\n"))
    assertEquals("line 2: expected `b`, actual `c`", AccessibilityTree.firstDifference("a\nb\n", "a\nc\n"))
    assertEquals("line 3: expected <end>, actual `x`", AccessibilityTree.firstDifference("a\nb", "a\nb\nx"))
  }
}
