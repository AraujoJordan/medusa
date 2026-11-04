package dev.medusa.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregationPlanTest {
  private val library = AggregationPlan.Module(":lib")
  private val app = AggregationPlan.Module(":app", application = true, dependencies = setOf(":lib"))

  @Test fun `unique app hosts transitive dependencies`() {
    val feature = AggregationPlan.Module(":feature", dependencies = setOf(":lib"))
    val result = AggregationPlan.create(listOf(library, feature, app.copy(dependencies = setOf(":feature"))))
    assertEquals(listOf(AggregationPlan.Group(":app", listOf(":app", ":feature", ":lib"))), result.groups)
  }

  @Test fun `opted out app still makes a library shared`() {
    val second = app.copy(path = ":other", automatic = false)
    val result = AggregationPlan.create(listOf(app, second, library))
    assertEquals(3, result.groups.size)
    assertEquals("shared by multiple applications", result.standaloneReasons[":lib"])
  }

  @Test fun `incompatible and explicitly configured modules remain standalone`() {
    for (lib in listOf(library.copy(settings = "different"), library.copy(automatic = false),
      library.copy(manualAggregator = true), library.copy(supported = false))) {
      assertEquals(2, AggregationPlan.create(listOf(app, lib)).groups.size)
    }
  }

  @Test fun `check exclusions do not change ownership`() {
    val result = AggregationPlan.create(listOf(app, app.copy(path = ":other", verifyOnCheck = false), library), true)
    assertEquals(2, result.groups.size)
    assertEquals("shared by multiple applications", result.standaloneReasons[":lib"])
    assertTrue(result.groups.none { ":other" in it.members })
  }

  @Test fun `cycles terminate and plans are deterministic`() {
    val lib = library.copy(dependencies = setOf(":feature"))
    val feature = AggregationPlan.Module(":feature", dependencies = setOf(":lib"))
    val modules = listOf(app, lib, feature)
    assertEquals(AggregationPlan.create(modules), AggregationPlan.create(modules.reversed()))
  }

  @Test fun `excluded host does not absorb check tasks`() {
    val result = AggregationPlan.create(listOf(app.copy(verifyOnCheck = false), library), true)
    assertEquals(listOf(AggregationPlan.Group(":lib", listOf(":lib"))), result.groups)
  }
}
