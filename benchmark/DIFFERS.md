# Which differ flags what, and how fast

Machine: macOS-27.0.1-arm64-arm-64bit-Mach-O. odiff: odiff 4.5.0 - SIMD first pixel-by-pixel image comparison tool. ImageMagick: Version: ImageMagick 7.1.2-35 Q16-HDRI aarch64 acaf7e418:20261031 https://imagemagick.org.

Corpus: `Button_PrimaryButtonPreview.png` (339x210) and copies with one controlled change each (`benchmark/differs.py`).
Tolerance is Medusa's default `maxPercentDifference = 0.01`; `pass` means the comparer accepts the image, `FAIL x%` shows the
difference it measured. What the percentage means depends on the differ (Paparazzi: summed channel difference;
yiq and odiff: share of pixels over the threshold; magick AE: alpha weighted share of pixels over the fuzz; magick RMSE: normalised error).

| image | offbytwo (Paparazzi default) | pixelperfect | yiq t=0.1 | yiq t=0.1 aa | yiq t=0 | odiff t=0.1 | odiff t=0.1 aa | magick AE fuzz 2% | magick RMSE |
|---|---|---|---|---|---|---|---|---|---|
| alpha99 | FAIL 100.00% | FAIL 100.00% | pass | pass | FAIL 98.68% | pass | pass | pass | FAIL 1.03% |
| block_12x12 | FAIL 0.11% | FAIL 0.11% | FAIL 0.20% | FAIL 0.20% | FAIL 0.20% | FAIL 0.20% | FAIL 0.20% | FAIL 0.08% | FAIL 2.49% |
| blue_plus1 | pass | FAIL 0.13% | pass | pass | FAIL 98.69% | pass | pass | pass | FAIL 0.19% |
| blue_plus3 | FAIL 0.39% | FAIL 0.39% | pass | pass | FAIL 98.69% | pass | pass | pass | FAIL 0.58% |
| blur_0_4 | FAIL 0.19% | FAIL 0.20% | FAIL 0.02% | pass | FAIL 5.00% | FAIL 0.02% | pass | FAIL 0.13% | FAIL 0.92% |
| dot1 | pass | pass | pass | pass | pass | pass | pass | pass | FAIL 0.32% |
| dot5 | pass | pass | pass | pass | pass | pass | pass | pass | FAIL 0.71% |
| identical | pass | pass | pass | pass | pass | pass | pass | pass | pass |
| one_px_wider | FAIL 0.15% | FAIL 0.15% | FAIL 0.29% | FAIL 0.29% | FAIL 0.29% | FAIL size | FAIL size | FAIL size | FAIL size |
| rgb_plus3 | FAIL 1.16% | FAIL 1.16% | pass | pass | FAIL 98.69% | pass | pass | pass | FAIL 1.01% |
| rgb_plus8 | FAIL 2.32% | FAIL 2.32% | pass | pass | FAIL 98.69% | pass | pass | FAIL 0.78% | FAIL 2.08% |
| shift1 | FAIL 0.63% | FAIL 0.63% | FAIL 1.78% | FAIL 0.04% | FAIL 2.35% | FAIL 1.78% | FAIL 0.04% | FAIL 0.47% | FAIL 4.47% |
| two_px_larger | FAIL 0.76% | FAIL 0.76% | FAIL 1.52% | FAIL 1.52% | FAIL 1.52% | FAIL size | FAIL size | FAIL size | FAIL size |

| comparer | ms per comparison |
|---|---|
| offbytwo (Paparazzi default) | 4.7 |
| pixelperfect | 4.9 |
| yiq t=0.1 | 4.0 |
| yiq t=0.1 aa | 4.1 |
| yiq t=0 | 4.0 |
| odiff t=0.1 | 3.0 |
| odiff t=0.1 aa | 3.0 |
| magick AE fuzz 2% | 21.0 |
| magick RMSE | 21.6 |
| odiff t=0.1, one process each | 8.4 |
