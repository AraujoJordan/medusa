package dev.medusa.core

import java.io.File

/** Writes an HTML report for humans and a JUnit XML file for CI. */
object Report {
  fun write(outputDir: File, results: List<VerifyResult>, orphans: List<File>, metadata: Map<String, String> = emptyMap()): File {
    val document = kotlinx.serialization.json.buildJsonObject {
      put("metadata", kotlinx.serialization.json.buildJsonObject { metadata.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(DiagnosticText.redact(v))) } })
      put("results", kotlinx.serialization.json.JsonArray(results.map { result -> kotlinx.serialization.json.buildJsonObject {
        put("name", kotlinx.serialization.json.JsonPrimitive(result.name))
        put("outcome", kotlinx.serialization.json.JsonPrimitive(DiagnosticText.redact(result.outcome.toString())))
        put("recovered", kotlinx.serialization.json.JsonPrimitive(result.recovered))
        put("infrastructure", kotlinx.serialization.json.JsonPrimitive(result.infrastructure))
        put("attempts", kotlinx.serialization.json.JsonArray(result.attempts.map { attempt -> kotlinx.serialization.json.buildJsonObject {
          put("number", kotlinx.serialization.json.JsonPrimitive(attempt.number))
          put("outcome", kotlinx.serialization.json.JsonPrimitive(DiagnosticText.redact(attempt.outcome.toString())))
          put("actual", kotlinx.serialization.json.JsonPrimitive(attempt.actual?.absolutePath))
        } }))
      } }))
    }
    File(outputDir, "results.json").apply { parentFile.mkdirs(); writeText(document.toString()) }
    writeJUnit(File(outputDir, "test-results/medusa.xml"), results)
    val html = File(outputDir, "report/index.html")
    html.parentFile.mkdirs()
    html.writeText(renderHtml(html.parentFile, results, orphans).replace("<h1>Medusa</h1>", "<h1>Medusa</h1><pre>${escape(metadata.toString())}</pre>"))
    return html
  }

  private fun writeJUnit(file: File, results: List<VerifyResult>) {
    file.parentFile.mkdirs()
    val failures = results.count { it.outcome !is Outcome.Passed }
    val cases = results.joinToString("\n") { r ->
      val name = escape(r.name.substringAfterLast('.'))
      val cls = escape(r.name.substringBeforeLast('.', "medusa"))
      when (val o = r.outcome) {
        Outcome.Passed -> if (!r.recovered) """  <testcase classname="$cls" name="$name"/>""" else
          """  <testcase classname="$cls" name="$name"><system-out>${escape("FLAKY: recovered after failure; " + r.attempts.toString())}</system-out></testcase>"""
        is Outcome.Missing ->
          """  <testcase classname="$cls" name="$name"><failure message="No golden image. Run medusaRecord."/></testcase>"""
        is Outcome.Failed ->
          """  <testcase classname="$cls" name="$name"><failure message="${escape(o.message)}"/></testcase>"""
      }
    }
    file.writeText(
      """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="medusa" tests="${results.size}" failures="$failures" errors="0">
$cases
</testsuite>
""",
    )
  }

  private fun renderHtml(reportDir: File, results: List<VerifyResult>, orphans: List<File>): String {
    fun rel(f: File): String {
      val relative = f.relativeToOrSelf(reportDir)
      val uri = if (relative.isAbsolute) relative.toURI().toASCIIString()
        else java.net.URI(null, null, relative.path.replace(File.separatorChar, '/'), null).toASCIIString()
      return escape(uri)
    }
    val rows = results.joinToString("\n") { r ->
      val artifacts = r.attempts.flatMap { attempt -> listOfNotNull(attempt.actual, (attempt.outcome as? Outcome.Failed)?.delta) }.distinct()
        .joinToString(" ") { "<a href=\"${rel(it)}\">${escape(it.name)}</a>" }
      val history = "<details><summary>Attempts${if (r.recovered) " — FLAKY recovered pass" else ""}</summary><pre>${escape(r.attempts.toString())}</pre>$artifacts</details>"
      when (val o = r.outcome) {
        Outcome.Passed -> "<tr class=pass><td>${escape(r.name)}</td><td>${if (r.recovered) "FLAKY recovered pass" else "passed"}</td><td>$history</td></tr>"
        is Outcome.Missing -> "<tr class=fail><td>${escape(r.name)}</td><td>no golden: run medusaRecord</td><td></td></tr>"
        is Outcome.Failed -> {
          val images = listOfNotNull(o.delta, o.actual).joinToString("") {
            val caption = if (it == o.delta) "expected | delta | actual" else "actual"
            "<figure><img src=\"${rel(it)}\"><figcaption>$caption</figcaption></figure>"
          }
          "<tr class=fail><td>${escape(r.name)}</td><td>${escape(o.message)}</td><td class=imgs>$images$history</td></tr>"
        }
      }
    }
    val orphanHtml = if (orphans.isEmpty()) "" else
      "<h2>Unused golden images</h2><ul>${orphans.joinToString("") { "<li>${escape(it.path)}</li>" }}</ul>"
    return """<!doctype html><html><head><meta charset="utf-8"><title>Medusa report</title><style>
body{font:14px system-ui;margin:24px}table{border-collapse:collapse;width:100%}td{border-bottom:1px solid #ddd;padding:6px;vertical-align:top}
tr.fail td:first-child{color:#b00020;font-weight:600}.imgs{display:flex;gap:12px}figure{margin:0}img{max-width:720px;background:#eee}
</style></head><body><h1>Medusa</h1><p>${results.count { it.outcome is Outcome.Passed }} passed, ${results.count { it.outcome !is Outcome.Passed }} failed</p>
<table>$rows</table>$orphanHtml</body></html>"""
  }

  private fun escape(s: String) = DiagnosticText.redact(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
