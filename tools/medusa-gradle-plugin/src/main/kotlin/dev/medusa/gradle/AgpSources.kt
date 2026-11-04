package dev.medusa.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Project

/**
 * Adds the Java and Kotlin source directories AGP reports for the debug build type (the variant the Android CLI
 * renders) to [MedusaExtension.sourceDirs], so previews in flavor, generated or custom source sets are found.
 * Kept in its own class so that a missing AGP only fails here, with a [LinkageError] the plugin catches.
 */
internal object AgpSources {
  @Suppress("UNCHECKED_CAST")
  fun addTo(project: Project, extension: MedusaExtension) {
    val components = project.extensions.findByName("androidComponents") as? AndroidComponentsExtension<*, *, Variant> ?: return
    components.onVariants(components.selector().withBuildType("debug")) { variant ->
      variant.sources.java?.static?.let { extension.sourceDirs.from(it) }
      variant.sources.kotlin?.static?.let { extension.sourceDirs.from(it) }
    }
  }
}
