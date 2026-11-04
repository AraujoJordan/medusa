# Choosing how images are compared

`differ` selects the algorithm. Every one produces the same failure image (expected | diff | actual) and report.

| `differ` | What it is | `maxPercentDifference` means |
|---|---|---|
| `odiff` (**default**) | the external [odiff](https://github.com/dmtrKovalenko/odiff) binary (installed by Medusa, see [setup](setup.md)), through a pool of `odiff --server` processes | share of pixels over `odiff.threshold` |
| `yiq` | in-process Kotlin port of odiff's algorithm (YIQ colour distance, optional antialiasing detection); also what `odiff` falls back to | share of pixels over `yiq.threshold` |
| `magick` | external ImageMagick `compare` (`brew install imagemagick`) | `AE`: share of pixels over `fuzz`; `RMSE`, `MAE`, `DSSIM`: normalised error in percent; `PSNR`: needs at least `minPsnr` dB |
| `offbytwo`, `pixelperfect`, `mssim`, `sift`, `flip`, `de2000` | Paparazzi's own differs, copied verbatim; `offbytwo` is Paparazzi's default | Paparazzi's rule: summed channel difference |

Paparazzi's tolerance and odiff's behave differently (see below), so verdicts are not interchangeable: if you move an existing project from
Paparazzi, `differ = "offbytwo"` gives its exact rule.

## Paparazzi's rule

`tools/medusa-core/src/main/kotlin/app/cash/paparazzi/` is Paparazzi's `Differ` and its six differs, copied
verbatim from a pinned upstream commit (Apache-2.0, `tools/medusa-core/PAPARAZZI_VENDORED.md`). The pass/fail rule is
Paparazzi's `ImageUtils.assertImageSimilar`: a difference above `maxPercentDifference` (default **0.01**) fails, and so does a
width or height that differs by 2px or more. `UpstreamParityTest` runs the released Paparazzi jar and Medusa on the same pairs
and requires the same verdict.

## `yiq` is odiff without the binary

`YiqVsOdiffParityTest` requires exactly the same differing-pixel count as the installed
odiff 4.5.0 on 96 combinations (thresholds 0 to 0.3, antialiasing on and off, shifted and recoloured antialiased text, alpha).
The shipped 4.5.0 truncates its fixed-point colour coefficients although its source rounds them, and the port follows the binary.

## Things that will surprise you

All measured; see [`benchmark/DIFFERS.md`](../benchmark/DIFFERS.md) and [`benchmark/SPIKE.md`](../benchmark/SPIKE.md).

- **odiff's default threshold of 0.1 hides colour drift completely, and this is Medusa's default.** A +3 level change on every pixel
  reports 0 differing pixels, while `threshold = 0` flags 98.7% of them. It is a cliff, not a gradual tolerance. Paparazzi's `offbytwo` lets
  1 to 2 levels through and fails 3.
- **The default tolerance is tiny**: 0.01% of a 339x210 image is about 7 whole pixels, so a handful of changed pixels passes.
- **External tools fail any size change.** ImageMagick compares different-size images silently (a 1px wider image can pass), so Medusa
  checks the size first and reports `size changed from AxB to CxD`. Paparazzi's differs tolerate 1px.
- `magick` ignores exit codes (PSNR of identical images exits 1) and reads the printed value. Its `RMSE`/`MAE`/`DSSIM` are far
  more sensitive to isolated pixels than `AE`, so give them their own `maxPercentDifference`.
- Paparazzi's `sift` rejects identical **flat-colour** images, in the released jar too (pinned by a test). Real previews are not flat.
- A comparison costs 3 to 5 ms in process or via `odiff --server` (8 ms with one odiff process per image) and about 21 ms with
  ImageMagick, on 339x210 images (the timed pair includes drawing the failure image), against roughly 30 to 60 ms of rendering per preview.
