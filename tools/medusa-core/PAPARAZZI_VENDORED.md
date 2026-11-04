# Vendored Paparazzi image comparison

`src/main/kotlin/app/cash/paparazzi/` is copied from https://github.com/cashapp/paparazzi (Apache-2.0, see `LICENSE.txt` next to it)
so that Medusa judges images with the same code as Paparazzi.

- Upstream commit: `41ca47841f4894671c25da76150de92679fcdcf6` (release 2.0.0-alpha05.1 line)
- Copied verbatim from `paparazzi/src/main/java/app/cash/paparazzi/`: `Differ.kt`, `internal/differs/{OffByTwo,PixelPerfect,Mssim,Sift,Flip,DeltaE2000}.kt`
- Only change: `Mssim.kt` lost an unused `androidx.compose.ui.util.fastCoerceAtLeast` import (marked with a comment).
- The pass/fail decision (`ImageUtils.compareImages` + `assertImageSimilar`) is re-implemented in `dev.medusa.core.PaparazziComparison`
  because upstream only exposes it by throwing `AssertionError` and printing; the rules are the same.

To update: re-run the copy at a newer commit, update the SHA here, and run `:medusa-core:test` (the parity tests compare against these differs).
