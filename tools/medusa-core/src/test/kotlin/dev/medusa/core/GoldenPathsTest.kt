package dev.medusa.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldenPathsTest {
  private val root = createTempDirectory("medusa-src").toFile()

  private fun source(path: String, text: String): File =
    File(root, path).also { it.parentFile.mkdirs(); it.writeText(text) }

  private fun preview(fqn: String, provider: Boolean = false) =
    PreviewInfo(fqn, parameterProvider = if (provider) ParameterProvider("p.Provider") else null)

  @Test
  fun `golden lives flat next to the source file`() {
    val file = source("dev/app/Button.kt", "package dev.app\nfun PrimaryButtonPreview() {}")
    val snapshot = Snapshot(preview("dev.app.ButtonKt.PrimaryButtonPreview"), file)

    assertEquals(File(file.parentFile, "Button_PrimaryButtonPreview.png"), snapshot.goldenFile(0))
    assertEquals("dev.app.ButtonKt.PrimaryButtonPreview", snapshot.displayName(0))
  }

  @Test
  fun `parameterized previews get an index per value`() {
    val file = source("dev/app/Button.kt", "fun Labels() {}")
    val snapshot = Snapshot(preview("dev.app.ButtonKt.Labels", provider = true), file)

    assertEquals("Button_Labels_0.png", snapshot.goldenFile(0).name)
    assertEquals("Button_Labels_1.png", snapshot.goldenFile(1).name)
    assertEquals("dev.app.ButtonKt.Labels[1]", snapshot.displayName(1))
  }

  @Test
  fun `multipreview entries are indexed even without a parameter provider`() {
    val file = source("dev/app/Button.kt", "fun Themes() {}")
    val snapshot = Snapshot(preview("dev.app.ButtonKt.Themes"), file, entryCount = 3)

    assertTrue(snapshot.indexed)
    assertEquals("Button_Themes_0.png", snapshot.goldenFile(0).name)
    assertEquals("Button_Themes_2.png", snapshot.goldenFile(2).name)
    assertEquals("dev.app.ButtonKt.Themes[2]", snapshot.displayName(2))
  }

  @Test
  fun `a single entry preview without provider is not indexed`() {
    val file = source("dev/app/Button.kt", "fun Plain() {}")

    assertFalse(Snapshot(preview("dev.app.ButtonKt.Plain"), file).indexed)
  }

  @Test
  fun `existing goldens match every index but not other previews`() {
    val file = source("dev/app/Button.kt", "fun Themes() {}")
    listOf("Button_Themes_0.png", "Button_Themes_1.png", "Button_Themes_10.png", "Button_ThemesOther_0.png", "Button_Themes.png")
      .forEach { source("dev/app/$it", "x") }

    val indexed = Snapshot(preview("dev.app.ButtonKt.Themes"), file, entryCount = 2).existingGoldens().map { it.name }
    val single = Snapshot(preview("dev.app.ButtonKt.Themes"), file).existingGoldens().map { it.name }

    assertEquals(listOf("Button_Themes_0.png", "Button_Themes_1.png", "Button_Themes_10.png"), indexed)
    assertEquals(listOf("Button_Themes.png"), single)
  }

  @Test
  fun `locates the file by the Kt facade convention`() {
    val file = source("dev/app/Button.kt", "package dev.app\n@Composable\nfun PrimaryButtonPreview() {}")

    assertEquals(file, SourceLocator.locate(preview("dev.app.ButtonKt.PrimaryButtonPreview"), listOf(root)))
  }

  @Test
  fun `falls back to scanning when the facade name does not match the file`() {
    val file = source("dev/app/Components.kt", "@file:JvmName(\"ButtonKt\")\npackage dev.app\nfun PrimaryButtonPreview() {}")

    assertEquals(file, SourceLocator.locate(preview("dev.app.ButtonKt.PrimaryButtonPreview"), listOf(root)))
  }

  @Test
  fun `a convention match that does not declare the function is skipped`() {
    source("dev/app/Button.kt", "package dev.app\nfun Other() {}")
    val real = source("dev/app/Screens.kt", "package dev.app\nfun PrimaryButtonPreview() {}")

    assertEquals(real, SourceLocator.locate(preview("dev.app.ButtonKt.PrimaryButtonPreview"), listOf(root)))
  }

  @Test
  fun `same function name in two packages resolves by package`() {
    source("a/One.kt", "package a\nfun SamePreview() {}")
    val b = source("b/Two.kt", "package b\nfun SamePreview() {}")

    assertEquals(b, SourceLocator.locate(preview("b.WhateverKt.SamePreview"), listOf(root)))
  }

  @Test
  fun `unknown preview has no source`() {
    assertNull(SourceLocator.locate(preview("dev.app.ButtonKt.Missing"), listOf(root)))
  }

  @Test
  fun `filter matches fqn, class dot name, simple name and globs`() {
    val p = preview("dev.app.ButtonKt.PrimaryButtonPreview")

    assertTrue(PreviewFilter(emptyList()).matches(p))
    assertTrue(PreviewFilter(listOf("dev.app.ButtonKt.PrimaryButtonPreview")).matches(p))
    assertTrue(PreviewFilter(listOf("ButtonKt.PrimaryButtonPreview")).matches(p))
    assertTrue(PreviewFilter(listOf("PrimaryButtonPreview")).matches(p))
    assertTrue(PreviewFilter(listOf("*Button*")).matches(p))
    assertTrue(PreviewFilter(listOf("dev.app.*")).matches(p))
    assertTrue(PreviewFilter(listOf("Nope", "*Primary*")).matches(p))
    assertFalse(PreviewFilter(listOf("Button")).matches(p))
    assertFalse(PreviewFilter(listOf("*Dark*")).matches(p))
  }
}
