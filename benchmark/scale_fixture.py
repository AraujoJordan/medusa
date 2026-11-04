#!/usr/bin/env python3
"""Generate fixtures/scale: a shared :core and M independent Compose modules with a few previews each.

Used by benchmark/concurrency.py to find how many modules can verify at once. Usage: scale_fixture.py [M=16] [output dir, default fixtures/scale]
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "fixtures/scale"
SRC = ROOT / "fixtures/multimodule"


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def module_build(ns: str, deps: str = "") -> str:
    return f'''plugins {{
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("io.github.araujojordan.medusa")
}}

android {{
  namespace = "{ns}"
  compileSdk = 36
  defaultConfig {{ minSdk = 24 }}
  buildFeatures {{ compose = true }}
}}

dependencies {{
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
{deps}}}
'''


def main(m: int, out: Path = OUT):
    global OUT
    OUT = out
    shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir(parents=True)
    for item in ("gradle", "gradlew", "gradle.properties", "local.properties"):
        s, d = SRC / item, OUT / item
        shutil.copytree(s, d) if s.is_dir() else shutil.copy2(s, d)
    names = [f"m{i:02d}" for i in range(1, m + 1)]
    write(OUT / "settings.gradle.kts", (SRC / "settings.gradle.kts").read_text().replace(
        'rootProject.name = "multimodule"', 'rootProject.name = "scale"').split("include(")[0]
        + 'include(":core", ' + ", ".join(f'":{n}"' for n in names) + ")\n")
    write(OUT / "build.gradle.kts", (SRC / "build.gradle.kts").read_text())
    write(OUT / "core/build.gradle.kts", module_build("sc.core"))
    write(OUT / "core/src/main/kotlin/sc/core/Core.kt", "package sc.core\n\nimport androidx.compose.material3.Text\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun CoreLabel(text: String) = Text(text)\n")
    for n in names:
        write(OUT / f"{n}/build.gradle.kts", module_build(f"sc.{n}", '  implementation(project(":core"))\n'))
        body = "package sc.%s\n\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.tooling.preview.Preview\nimport sc.core.CoreLabel\n\n" % n
        for k in range(3):
            body += '@Preview(showBackground = true)\n@Composable\nfun %sPreview%d() = CoreLabel("module %s preview %d")\n\n' % (n.upper(), k, n, k)
        write(OUT / f"{n}/src/main/kotlin/sc/{n}/Screen.kt", body)
    print(f"generated {m} modules in {OUT}")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 16, Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else OUT)
