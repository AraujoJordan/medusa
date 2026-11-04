package dev.medusa.core

import java.io.Serializable

data class ReliabilityOptions(
  val discoveryTimeoutSeconds: Long = 600,
  val renderTimeoutSeconds: Long = 1800,
  val comparisonTimeoutSeconds: Long = 60,
  val visualRetryCount: Int = 0,
  val allowRecoveredPass: Boolean = false,
) : Serializable {
  init {
    require(discoveryTimeoutSeconds > 0 && renderTimeoutSeconds > 0 && comparisonTimeoutSeconds > 0) { "Timeouts must be positive" }
    require(visualRetryCount in 0..3) { "visualRetryCount must be between 0 and 3" }
    require(!allowRecoveredPass || visualRetryCount > 0) { "allowRecoveredPass requires visualRetryCount > 0" }
  }
}

internal object DiagnosticText {
  fun redact(text: String): String = text
    .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
    .replace(Regex("(?i)(authorization\\s*[:=]\\s*)([^\\r\\n]+)"), "$1[REDACTED]")
    .replace(Regex("(?i)((?:password|token|secret|api[_-]?key)[\\w.-]*\\s*[=:]\\s*)([^\\s,;]+)"), "$1[REDACTED]")
    .replace(Regex("(https?://)[^/@\\s]+:[^/@\\s]+@"), "$1[REDACTED]@")
}
