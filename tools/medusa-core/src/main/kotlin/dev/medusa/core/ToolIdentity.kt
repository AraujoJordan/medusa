package dev.medusa.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Probe once per binary revision; version failures never change screenshot verdicts. */
object ToolIdentity {
  private val versions = ConcurrentHashMap<String, String>()
  fun version(binary: File, argument: String = "--version"): String = versions.computeIfAbsent("${binary.canonicalPath}:${binary.lastModified()}:${binary.length()}:$argument") {
    runCatching {
      val process = ProcessBuilder(listOf(binary.absolutePath) + (if (argument == "-V") listOf("--no-metrics", argument) else listOf(argument))).redirectErrorStream(true).start()
      ProcessSupervisor.bounded(process, 3) {
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        DiagnosticText.redact(output).take(2048)
      }
    }.getOrElse { "unavailable: ${it.javaClass.simpleName}" }
  }
}
