#!/bin/bash
# Regenerates docs/media/hero.png from real goldens of the sample app (ImageMagick only).
# Needs: ImageMagick 7 (`magick`) and a monospace + sans font file (macOS system fonts by default; override with MONO=/SANS=).
# The goldens come from sample/.../ProfileCard.kt (`./gradlew :sample:medusaRecord --preview='ProfileCard*'`);
# the verify count shown is the real output of `./gradlew :sample:medusaVerify` (set COUNT=n to update it).
#
# failure.png is a real failure image: flip `darkTheme = true` in PrimaryButtonPreview (sample/.../Button.kt), run
# `./gradlew :sample:medusaVerify`, copy sample/build/medusa/failures/delta-*PrimaryButtonPreview.png here as /tmp/failure-raw.png,
# revert the edit, then run this script (it adds the caption strip).
set -euo pipefail
cd "$(dirname "$0")/../.."

MONO=${MONO:-$(ls /System/Library/Fonts/SFNSMono.ttf /System/Library/Fonts/Menlo.ttc "/System/Library/Fonts/Supplemental/Courier New.ttf" 2>/dev/null | head -1)}
SANS=${SANS:-$(ls /System/Library/Fonts/SFNS.ttf /System/Library/Fonts/Helvetica.ttc 2>/dev/null | head -1)}
[ -n "$MONO" ] && [ -n "$SANS" ] || { echo "No fonts found: set MONO= and SANS= to font files" >&2; exit 1; }
COUNT=${COUNT:-42}

G=sample/src/main/kotlin/dev/medusa/sample/ui/components
LIGHT=$G/ProfileCard_ProfileCardLightPreview.png
DARK=$G/ProfileCard_ProfileCardDarkPreview.png
BG='#0d1117'; PANEL='#161b22'; EDGE='#30363d'; TEXT='#e6edf3'; DIM='#8b949e'
KW='#ff7b72'; ANN='#d2a8ff'; FN='#79c0ff'; GREEN='#3fb950'

# Cards: downscaled only (945 px wide goldens -> 440), rounded corners, thin border.
card() {
  magick "$1" -resize 440x377! -alpha set \
    \( -size 440x377 xc:black -fill white -draw "roundrectangle 0,0 439,376 22,22" \) \
    -alpha off -compose CopyOpacity -composite \
    -stroke "$EDGE" -strokewidth 2 -fill none -draw "roundrectangle 1,1 438,375 22,22" "$2"
}
T=$(mktemp -d)
card "$LIGHT" "$T/l.png"
card "$DARK" "$T/d.png"

magick -size 1600x800 "xc:$BG" \
  -fill "$PANEL" -stroke "$EDGE" -strokewidth 2 -draw "roundrectangle 40,40 570,420 16,16" \
  -stroke none -font "$MONO" -pointsize 25 \
  -fill "$ANN" -annotate +72+100 '@PreviewLightDark' \
  -fill "$ANN" -annotate +72+140 '@Preview(widthDp = 360)' \
  -fill "$KW" -annotate +72+190 '@Composable' \
  -fill "$KW" -annotate +72+230 'fun ' -fill "$FN" -annotate +132+230 'ProfileCardPreview' -fill "$TEXT" -annotate +402+230 '() {' \
  -fill "$FN" -annotate +104+270 'ProfileCard' -fill "$TEXT" -annotate +269+270 '(...)' \
  -fill "$TEXT" -annotate +72+310 '}' \
  -fill "$DIM" -annotate +72+380 '// that is the whole test' \
  -stroke "$GREEN" -strokewidth 5 -fill none -draw "line 590,230 626,230" -draw "polyline 614,216 628,230 614,244" -stroke none \
  "$T/l.png" -geometry +640+40 -composite \
  "$T/d.png" -geometry +1120+40 -composite \
  -fill "$PANEL" -stroke "$EDGE" -strokewidth 2 -draw "roundrectangle 40,470 1560,760 16,16" \
  -stroke none -font "$MONO" -pointsize 25 \
  -fill "$DIM" -annotate +72+524 'ui/components/' \
  -fill "$TEXT" -annotate +104+572 'ProfileCard.kt' \
  -fill "$FN" -annotate +104+620 'ProfileCard_ProfileCardLightPreview.png' \
  -fill "$FN" -annotate +104+668 'ProfileCard_ProfileCardDarkPreview.png' \
  -fill "$DIM" -annotate +72+720 'goldens live next to the code that declares the preview' \
  -stroke "$GREEN" -strokewidth 8 -fill none -draw "polyline 1030,598 1050,620 1085,574" -stroke none \
  -fill "$GREEN" -font "$SANS" -pointsize 40 -annotate +1110+614 "medusaVerify" \
  -fill "$DIM" -font "$MONO" -pointsize 26 -annotate +1030+670 "$COUNT previews match" \
  -depth 8 docs/media/hero.png

# failure.png: caption strip under the raw expected | diff | actual image (docs/media/failure-raw.png).
if [ -f docs/media/failure-raw.png ]; then
  lab() { magick -size 339x50 "xc:$BG" -font "$SANS" -pointsize 30 -fill "$2" -gravity center -annotate +0+0 "$1" "$3"; }
  lab 'expected (golden)' "$TEXT" "$T/a.png"; lab 'diff' '#ff6b6b' "$T/b.png"; lab 'actual (this run)' "$TEXT" "$T/c.png"
  magick \( docs/media/failure-raw.png -bordercolor '#fafafa' -border 0 \) \
    \( "$T/a.png" "$T/b.png" "$T/c.png" +append \) \
    \( -size 1017x70 "xc:$BG" -font "$SANS" -pointsize 22 -fill "$DIM" -gravity center \
       -annotate +0+0 'Red marks the pixels that changed: a dark theme sneaked into the button (34.2% differ, 0.01% allowed).' \) \
    -append -depth 8 docs/media/failure.png
fi
rm -rf "$T"
