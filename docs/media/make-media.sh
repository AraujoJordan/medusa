#!/bin/bash
# Regenerates docs/media/hero.png from real goldens of the sample app (ImageMagick `montage`).
# failure.png is a real failure image: flip `darkTheme = true` in PrimaryButtonPreview (sample/.../Button.kt), run
# `./gradlew :sample:medusaVerify`, copy sample/build/medusa/failures/delta-*PrimaryButtonPreview.png here, then revert the edit.
set -euo pipefail
cd "$(dirname "$0")/../.."
G=sample/src/main/kotlin/dev/medusa/sample/ui/components
magick montage \
  $G/Button_PrimaryButtonPreview.png \
  $G/Button_PrimaryButtonDarkPreview.png \
  $G/Button_ButtonsColumnPreview.png \
  $G/PreviewParams_ParamBackgroundPreview.png \
  $G/PreviewParams_ParamTabletPreview.png \
  $G/CustomAnnotations_WidthsPreview_1.png \
  -tile 3x2 -geometry 400x250+18+18 -gravity center -background '#12151b' -depth 8 docs/media/hero.png
