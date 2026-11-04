package dev.medusa.core

import java.io.File

/** Writes an HTML report for humans and a JUnit XML file for CI. */
object Report {
  fun write(outputDir: File, results: List<VerifyResult>, orphans: List<File>): File {
    writeJUnit(File(outputDir, "test-results/medusa.xml"), results)
    val html = File(outputDir, "report/index.html")
    html.parentFile.mkdirs()
    html.writeText(renderHtml(html.parentFile, results, orphans))
    return html
  }

  private fun writeJUnit(file: File, results: List<VerifyResult>) {
    file.parentFile.mkdirs()
    val failures = results.count { it.outcome !is Outcome.Passed }
    val cases = results.joinToString("\n") { r ->
      val name = escape(r.name.substringAfterLast('.'))
      val cls = escape(r.name.substringBeforeLast('.', "medusa"))
      when (val o = r.outcome) {
        Outcome.Passed -> """  <testcase classname="$cls" name="$name"/>"""
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
    fun rel(f: File) = escape(f.relativeToOrSelf(reportDir).path.let { if (f.isAbsolute) "file://${f.absolutePath}" else it })
    val rows = results.joinToString("\n") { r ->
      when (val o = r.outcome) {
        Outcome.Passed -> "<tr class=pass><td>${escape(r.name)}</td><td>passed</td><td></td></tr>"
        is Outcome.Missing -> "<tr class=fail><td>${escape(r.name)}</td><td>no golden: run medusaRecord</td><td></td></tr>"
        is Outcome.Failed -> {
          val images = listOfNotNull(o.delta, o.actual).joinToString("") {
            val caption = if (it == o.delta) "expected | delta | actual" else "actual"
            "<figure><img src=\"${rel(it)}\"><figcaption>$caption</figcaption></figure>"
          }
          "<tr class=fail><td>${escape(r.name)}</td><td>${escape(o.message)}</td><td class=imgs>$images</td></tr>"
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

  private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
