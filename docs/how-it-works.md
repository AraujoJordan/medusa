# How it works

## The pipeline

```
android preview render --daemon? --fqn=... (one batch)
        |  each PNG is picked up the moment its closing IEND chunk is on disk
        v
     Channel  -->  N comparers on Dispatchers.Default  -->  verdicts / goldens written
```

Rendering is not waited for: while the CLI is still producing images, the finished ones are already being compared.
With `--info` Medusa logs when the first image was compared and when the last one was rendered. Measured on a real module,
the CLI's fixed cost per call (about 5 s without `--daemon`, 2 s with it) dwarfs the roughly 50 ms per extra preview, so
Medusa renders everything in **one** call by default; `renderParallelism` > 1 starts several calls at once, which works
but pays that fixed cost per call. See [`benchmark/SPIKE.md`](../benchmark/SPIKE.md) for the measurements.

## Where goldens live and what they are called

Golden images are stored **next to the Kotlin file that declares the preview**:

```
ui/components/
  Button.kt
  Button_PrimaryButtonPreview.png            # one @Preview
  Button_PrimaryButtonLabelsPreview_0.png    # @PreviewParameter or @PreviewLightDark / @PreviewFontScale / ...:
  Button_PrimaryButtonLabelsPreview_1.png    #   one image per value, numbered in declaration order
  Button_PrimaryButtonPreview.semantics.txt  # only with accessibilitySnapshots = true
```

- Golden names use the CLI's declaration-order index, not the preview's `name`, because the list returns names in a
  different order than the renders. Reordering the annotations inside a custom multipreview therefore renumbers its goldens:
  run `medusaCleanRecord`.
- Two previews with the same function name in one file (different classes or objects) get the class name in their golden names
  (`Screens_Light_Preview.png`); all other names are unchanged, and the name does not depend on `--preview`.
- Images are cropped to their content by the renderer, so a layout change shows up as a size change.

## Multipreviews and custom annotations

`@PreviewLightDark`, `@PreviewFontScale`, repeated `@Preview`, `@PreviewParameter` and **custom multipreview annotations** (also nested,
also defined in another module) all work: the CLI expands them from the compiled classes, and Medusa records one golden per image. A custom
multipreview combined with a `@PreviewParameter` gives entries x values images, numbered `_0` to `_N`. Examples:
[`sample/.../CustomAnnotations.kt`](../sample/src/main/kotlin/dev/medusa/sample/ui/components/CustomAnnotations.kt) and
[`fixtures/multimodule`](../fixtures/multimodule).

## Accessibility snapshots

With `accessibilitySnapshots = true`, Medusa also records a normalised text form of the CLI's component tree next to each golden
(`.semantics.txt`: components with semantics, in reading order, with bounds). It is Medusa's own format, not Paparazzi's rendered
accessibility legend.

## Source files

Medusa finds the `.kt` file that declares a preview by the Kotlin convention (`ButtonKt` is `Button.kt`) and falls back to searching the
debug variant's source directories (as AGP reports them) for `fun <PreviewName>`. Add more with `medusa { sourceDirs.from(...) }`.
