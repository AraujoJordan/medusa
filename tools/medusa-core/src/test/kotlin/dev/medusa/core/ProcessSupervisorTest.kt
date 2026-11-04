package dev.medusa.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessSupervisorTest {
  @Test fun `deadline includes a blocked output read and kills the process`() {
    val process = ProcessBuilder("bash", "-c", "sleep 30").start()
    val started = System.nanoTime()
    assertFailsWith<MedusaException> {
      ProcessSupervisor.bounded(process, 1) { process.inputStream.readBytes() }
    }
    process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
    assertFalse(process.isAlive)
    assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
  }
  @Test fun `exception diagnostics redact nested causes and terminal controls`() {
    val failure = MedusaException("token=private", IllegalStateException("password=private"))
    val trace = failure.stackTraceToString()
    assertFalse("private" in trace)
    assertTrue("REDACTED" in trace)
  }

}
