#!/usr/bin/env python3
"""Which differ flags which change, and how long a comparison takes. Writes benchmark/DIFFERS.md.

Needs ImageMagick (to make the corpus) and, for the odiff columns, odiff. The corpus is one real golden image plus
copies with controlled changes (exact 8-bit colour levels: 1 level is 0.392% of ImageMagick's quantum range).
"""
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = Path(__file__).resolve().parent / "corpus"
SOURCE = ROOT / "sample/src/main/kotlin/dev/medusa/sample/ui/components/Button_PrimaryButtonPreview.png"
LEVEL = 0.392  # percent of the quantum range per 8-bit level


def magick(*args):
    subprocess.run(["magick", *map(str, args)], check=True)


def build_corpus():
    shutil.rmtree(CORPUS, ignore_errors=True)
    CORPUS.mkdir(parents=True)
    magick(SOURCE, CORPUS / "base.png")
    magick(SOURCE, CORPUS / "identical.png")
    magick(SOURCE, "-channel", "B", "-evaluate", "Add", f"{LEVEL}%", "+channel", CORPUS / "blue_plus1.png")
    magick(SOURCE, "-channel", "B", "-evaluate", "Add", f"{3 * LEVEL}%", "+channel", CORPUS / "blue_plus3.png")
    magick(SOURCE, "-channel", "RGB", "-evaluate", "Add", f"{3 * LEVEL}%", "+channel", CORPUS / "rgb_plus3.png")
    magick(SOURCE, "-channel", "RGB", "-evaluate", "Add", f"{8 * LEVEL}%", "+channel", CORPUS / "rgb_plus8.png")
    magick(SOURCE, "-roll", "+1+0", CORPUS / "shift1.png")
    magick(SOURCE, "-fill", "black", "-draw", "point 20,20", CORPUS / "dot1.png")
    magick(SOURCE, "-fill", "black", "-draw", "point 20,20", "-draw", "point 40,20", "-draw", "point 60,20",
           "-draw", "point 80,20", "-draw", "point 100,20", CORPUS / "dot5.png")
    magick(SOURCE, "-alpha", "set", "-channel", "A", "-evaluate", "Multiply", "0.99", "+channel", CORPUS / "alpha99.png")
    magick(SOURCE, "-blur", "0x0.4", CORPUS / "blur_0_4.png")
    magick(SOURCE, "-fill", "black", "-draw", "rectangle 150,150 161,161", CORPUS / "block_12x12.png")
    magick(SOURCE, "-background", "white", "-extent", "340x210", CORPUS / "one_px_wider.png")
    magick(SOURCE, "-background", "white", "-extent", "341x212", CORPUS / "two_px_larger.png")


def main():
    if shutil.which("magick") is None:
        sys.exit("ImageMagick is needed to build the corpus: brew install imagemagick")
    build_corpus()
    start = time.time()
    proc = subprocess.run(
        ["./gradlew", "--console=plain", "-q", ":medusa-core:differMatrix", f"-Pcorpus={CORPUS}"],
        cwd=ROOT / "tools", capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit(proc.stdout + proc.stderr)
    def version(tool):
        if shutil.which(tool) is None:
            return "not installed"
        r = subprocess.run([tool, "--version"], capture_output=True, text=True)
        lines = (r.stdout + r.stderr).splitlines()
        return lines[0] if lines else "unknown version"

    versions = {t: version(t) for t in ("odiff", "magick")}
    out = ["# Which differ flags what, and how fast", "",
           f"Machine: {platform.platform()}. odiff: {versions['odiff']}. ImageMagick: {versions['magick']}.", "",
           f"Corpus: `{SOURCE.name}` (339x210) and copies with one controlled change each (`benchmark/differs.py`).",
           "Tolerance is Medusa's default `maxPercentDifference = 0.01`; `pass` means the comparer accepts the image, `FAIL x%` shows the",
           "difference it measured. What the percentage means depends on the differ (Paparazzi: summed channel difference;",
           "yiq and odiff: share of pixels over the threshold; magick AE: alpha weighted share of pixels over the fuzz; magick RMSE: normalised error).",
           "", proc.stdout.strip(), ""]
    (Path(__file__).resolve().parent / "DIFFERS.md").write_text("\n".join(out))
    print(proc.stdout)
    print(f"wrote benchmark/DIFFERS.md in {time.time() - start:.0f}s")


if __name__ == "__main__":
    main()
