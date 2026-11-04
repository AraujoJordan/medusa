package dev.medusa.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns the `--component-tree` JSON of `android preview render` into a small, stable text file that is easy to
 * review in a diff: only components that carry semantics, indented by semantic nesting, with sorted properties
 * and pixel bounds.
 *
 * ```
 * # medusa accessibility snapshot v1
 * Box role="Button" actions="OnClick" [42,53][297,158]
 *   Text text="Primary" [96,80][200,130]
 * ```
 */
object AccessibilityTree {
  const val HEADER = "# medusa accessibility snapshot v1"

  fun normalize(treeJson: String): String {
    val root = try {
      Json.parseToJsonElement(treeJson).jsonObject
    } catch (e: Exception) {
      throw MedusaException("The component tree is not valid JSON: ${e.message}", e)
    }
    val lines = mutableListOf(HEADER)
    root["components"]?.jsonArray?.flatMap { blocks(it.jsonObject) }?.sortedWith(READING_ORDER)?.forEach { it.appendTo(lines, 0) }
    return lines.joinToString("\n", postfix = "\n")
  }

  /** A component that carries semantics, with the semantic components below it. */
  private class Block(val top: Int, val left: Int, val text: String, val children: List<Block>) {
    fun appendTo(out: MutableList<String>, depth: Int) {
      out += "${"  ".repeat(depth)}$text"
      children.forEach { it.appendTo(out, depth + 1) }
    }
  }

  /**
   * The CLI does not list siblings in a stable order (the Scaffold's app bar title came first in one run and last in
   * the next, with identical pixels), so siblings are sorted in reading order: top, left, then text.
   */
  private val READING_ORDER = compareBy<Block>({ it.top }, { it.left }, { it.text })

  /** Components without semantics are transparent: their semantic descendants belong to the nearest semantic ancestor. */
  private fun blocks(node: JsonObject): List<Block> {
    val children = (node["children"] as? JsonArray).orEmpty().flatMap { blocks(it.jsonObject) }
    val semantics = (node["semantics"] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: return children
    val props = semantics.entries.sortedBy { it.key }.joinToString(" ") { (k, v) ->
      val text = if (v is JsonPrimitive) v.content else v.toString()
      "$k=\"${text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
    }
    val bounds = node["bounds"] as? JsonObject
    fun v(k: String) = bounds?.get(k)?.jsonPrimitive?.intOrNull ?: 0
    val boundsText = if (bounds == null) "" else " [${v("left")},${v("top")}][${v("right")},${v("bottom")}]"
    val line = "${node["name"]?.jsonPrimitive?.content ?: "?"} $props$boundsText"
    return listOf(Block(v("top"), v("left"), line, children.sortedWith(READING_ORDER)))
  }

  /** First differing line as "line N: expected ... / actual ..." or null when equal. */
  fun firstDifference(expected: String, actual: String): String? {
    val e = expected.lines()
    val a = actual.lines()
    for (i in 0 until maxOf(e.size, a.size)) {
      val el = e.getOrNull(i)
      val al = a.getOrNull(i)
      if (el != al) return "line ${i + 1}: expected ${el?.let { "`$it`" } ?: "<end>"}, actual ${al?.let { "`$it`" } ?: "<end>"}"
    }
    return null
  }
}
