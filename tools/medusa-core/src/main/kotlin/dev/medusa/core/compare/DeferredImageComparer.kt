package dev.medusa.core.compare

import dev.medusa.core.Comparison
import java.io.File

/** Task-scoped, thread-safe initialization: new goldens need no comparison tool. */
class DeferredImageComparer(factory: () -> ImageComparer) : ImageComparer {
  private val delegate = lazy(factory)
  override fun compare(golden: File, actual: File): Comparison = delegate.value.compare(golden, actual)
  override fun close() {
    if (delegate.isInitialized()) delegate.value.close()
  }
}
