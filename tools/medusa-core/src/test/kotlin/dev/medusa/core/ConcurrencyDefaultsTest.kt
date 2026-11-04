package dev.medusa.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConcurrencyDefaultsTest {
  private fun cap(cores: Int, memGb: Double?, heapMb: Int? = 2048) =
    ConcurrencyDefaults.maxConcurrentBuilds(cores, memGb?.let { (it * 1024).toLong() }, heapMb)

  @Test
  fun `the measured machine gets the measured optimum`() {
    // 10 logical cores, 64 GB, 2 GB heap: caps 3 and 4 were fastest.
    assertTrue(cap(10, 64.0) in 3..4, "got ${cap(10, 64.0)}")
  }

  @Test
  fun `small machines run one build at a time`() {
    assertEquals(1, cap(1, 16.0))
    assertEquals(1, cap(2, 16.0))
    assertEquals(1, cap(4, 16.0))
  }

  @Test
  fun `cores scale it up until the maximum`() {
    assertEquals(2, cap(6, 64.0))
    assertEquals(3, cap(8, 64.0))
    assertEquals(5, cap(16, 64.0))
    assertEquals(ConcurrencyDefaults.MAX, cap(64, 512.0))
    assertEquals(ConcurrencyDefaults.MAX, cap(1024, 4096.0))
  }

  @Test
  fun `memory limits it before cores do`() {
    assertEquals(1, cap(32, 4.0), "4 GB cannot hold two builds with a 2 GB heap")
    assertEquals(2, cap(32, 8.0))
    assertEquals(1, cap(32, 2.0))
    assertTrue(cap(32, 16.0) < cap(32, 64.0))
  }

  @Test
  fun `a bigger heap limit means fewer concurrent builds`() {
    assertTrue(cap(32, 16.0, heapMb = 4096) < cap(32, 16.0, heapMb = 512))
    assertEquals(ConcurrencyDefaults.perBuildMemoryMb(null), ConcurrencyDefaults.perBuildMemoryMb(512))
    assertEquals(900L, ConcurrencyDefaults.perBuildMemoryMb(256), "never below what was measured on a small project")
  }

  @Test
  fun `unknown memory means only cores decide`() {
    assertEquals(3, cap(10, null))
  }

  @Test
  fun `the result is always between 1 and the maximum and never decreases with more cores or memory`() {
    var previous = 0
    for (cores in 1..80) {
      for (mem in listOf(0.5, 1.0, 4.0, 16.0, 64.0)) {
        val c = cap(cores, mem)
        assertTrue(c in 1..ConcurrencyDefaults.MAX, "cores=$cores mem=$mem -> $c")
      }
      val c = cap(cores, 64.0)
      assertTrue(c >= previous, "adding cores must not reduce the cap: $cores -> $c < $previous")
      previous = c
    }
    assertTrue((1..40).map { cap(16, it * 2.0) }.zipWithNext().all { (a, b) -> b >= a }, "adding memory must not reduce the cap")
  }

  @Test
  fun `the heap limit is read from jvmargs`() {
    assertEquals(2048, ConcurrencyDefaults.parseHeapMb("-Xmx2048m -Dfile.encoding=UTF-8"))
    assertEquals(2048, ConcurrencyDefaults.parseHeapMb("-Dfile.encoding=UTF-8 -Xmx2g"))
    assertEquals(1024, ConcurrencyDefaults.parseHeapMb("-Xmx1G"))
    assertEquals(512, ConcurrencyDefaults.parseHeapMb("-Xmx512M -XX:MaxMetaspaceSize=256m"))
    assertEquals(1, ConcurrencyDefaults.parseHeapMb("-Xmx1024k"))
    assertNull(ConcurrencyDefaults.parseHeapMb("-Dfile.encoding=UTF-8"))
    assertNull(ConcurrencyDefaults.parseHeapMb(null))
  }

  @Test
  fun `the explanation names every input so a surprising value on CI can be understood`() {
    val text = ConcurrencyDefaults.explain(4, 8 * 1024L, 2048)

    assertTrue("1 modules start the Android CLI at once (4 logical cores, 8.0 GB, nested builds with a 2048 MB heap" in text, text)
    assertTrue("medusa.maxConcurrentRenders" in text)
    assertTrue("unknown memory" in ConcurrencyDefaults.explain(4, null, null))
  }
}
