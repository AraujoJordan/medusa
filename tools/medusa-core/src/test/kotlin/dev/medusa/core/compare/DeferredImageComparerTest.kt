package dev.medusa.core.compare

import dev.medusa.core.Comparison
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DeferredImageComparerTest {
  @Test fun `unused comparer does not initialize`() {
    DeferredImageComparer { error("must not initialize") }.close()
  }
  @Test fun `comparisons reuse delegate and close it`() {
    var created = 0
    var closed = 0
    val comparer = DeferredImageComparer {
      created++
      object : ImageComparer {
        override fun compare(golden: File, actual: File) = Comparison.Passed(0f)
        override fun close() { closed++ }
      }
    }
    repeat(3) { comparer.compare(File("golden"), File("actual")) }
    comparer.close()
    assertEquals(1, created)
    assertEquals(1, closed)
  }
}
