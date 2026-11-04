package dev.medusa.core

import java.io.File

/**
 * A preview function, the source file that declares it, and where its golden images live.
 *
 * @param entryCount how many entries `preview list` returned for this FQN. A multipreview annotation
 * (`@PreviewLightDark`, `@PreviewFontScale`, or several `@Preview`s) yields several entries with the same FQN.
 */
data class Snapshot(
  val preview: PreviewInfo,
  val sourceFile: File,
  val entryCount: Int = 1,
  /**
   * Set when another preview in the same file has the same function name (for example in a different object or
   * class): both then include their class name in the golden file name, so they do not overwrite each other.
   */
  val qualifier: String? = null,
) {
  private val namePart: String get() = (qualifier?.let { "${it}_" }.orEmpty()) + preview.name

  /**
   * True when the CLI renders several images for this function: a `@PreviewParameter` provider or a multipreview.
   * Then every image gets an `_<index>` suffix (declaration order, the CLI's own index), because the entries
   * of a multipreview can only be told apart by index.
   */
  val indexed: Boolean get() = preview.hasParameterProvider || entryCount > 1

  /** `Button_PrimaryButtonPreview.png`, or `Button_PrimaryButtonPreview_<index>.png` when [indexed]. */
  fun goldenFile(index: Int): File {
    val stem = sourceFile.nameWithoutExtension
    val suffix = if (indexed) "_$index" else ""
    return File(sourceFile.parentFile, "${stem}_$namePart$suffix.png")
  }

  /** `Button_PrimaryButtonPreview.semantics.txt`: the accessibility snapshot that belongs to [goldenFile]. */
  fun semanticsFile(index: Int): File = File(goldenFile(index).path.removeSuffix(".png") + ".semantics.txt")

  /** All golden files (images and accessibility snapshots) of this preview that exist on disk, every index. */
  fun existingGoldens(): List<File> {
    val stem = Regex.escape(sourceFile.nameWithoutExtension)
    val name = Regex.escape(namePart)
    val index = if (indexed) """_\d+""" else ""
    val pattern = Regex("""${stem}_$name$index\.(png|semantics\.txt)""")
    return sourceFile.parentFile.listFiles { f -> f.isFile && pattern.matches(f.name) }?.sortedBy { it.name }.orEmpty()
  }

  /** Name used in reports, failure files and JUnit XML. */
  fun displayName(index: Int): String {
    val suffix = if (indexed) "[$index]" else ""
    return "${preview.className}.${preview.name}$suffix"
  }
}

object SourceLocator {
  /**
   * Finds the .kt file declaring the preview. Tries the Kotlin facade-class convention first
   * (`dev.app.ButtonKt` -> `dev/app/Button.kt`), then falls back to scanning for `fun <Name>`,
   * which covers `@file:JvmName`, previews inside classes/objects and mismatched file names.
   */
  fun locate(preview: PreviewInfo, sourceRoots: List<File>): File? {
    val packagePath = preview.className.substringBeforeLast('.', "").replace('.', '/')
    val simpleClass = preview.className.substringAfterLast('.').substringBefore('$')
    val stems = listOfNotNull(simpleClass.removeSuffix("Kt").takeIf { it.isNotEmpty() }, simpleClass).distinct()

    val declaration = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?${Regex.escape(preview.name)}\s*\(""")

    for (root in sourceRoots) {
      for (stem in stems) {
        val candidate = File(root, "$packagePath/$stem.kt".removePrefix("/"))
        if (candidate.isFile && declaration.containsMatchIn(candidate.readText())) return candidate
      }
    }
    val matches = sourceRoots.asSequence()
      .filter { it.isDirectory }
      .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
      .filter { declaration.containsMatchIn(it.readText()) }
      .toList()
    // Several hits are ambiguous (same function name in different packages): prefer the matching package.
    return matches.singleOrNull()
      ?: matches.firstOrNull { it.readText().contains(Regex("""(?m)^package\s+${Regex.escape(preview.className.substringBeforeLast('.', ""))}\s*$""")) }
  }
}

/** `--preview=` matching: a glob (`*`, `?`) against the full FQN, `Class.Name`, or the simple name. */
class PreviewFilter(patterns: List<String>) {
  private val regexes = patterns.map { glob ->
    Regex(glob.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } })
  }

  val isEmpty: Boolean get() = regexes.isEmpty()

  fun matches(preview: PreviewInfo): Boolean {
    if (regexes.isEmpty()) return true
    val candidates = listOf(
      preview.methodFqn,
      "${preview.className.substringAfterLast('.')}.${preview.name}",
      preview.name,
    )
    return regexes.any { regex -> candidates.any { regex.matches(it) } }
  }
}
