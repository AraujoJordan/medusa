#!/usr/bin/env python3
"""Generate the benchmark module `bench/` with N composables.

Every composable `CardK` gets
  * a `@Preview` function `CardKPreview` in src/main (what Medusa renders), and
  * a test `cardK` in src/test (what Paparazzi renders), 10 tests per class so Gradle can fork per class.

Both tools therefore render exactly the same composable code. Usage: generate.py N
"""
import shutil
import sys
from pathlib import Path

PER_FILE = 10
ROOT = Path(__file__).resolve().parent.parent
BENCH = ROOT / "bench"

BUILD = '''plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.paparazzi)
  id("io.github.araujojordan.medusa")
}

android {
  namespace = "bench"
  compileSdk = 37
  defaultConfig { minSdk = 24 }
  buildFeatures { compose = true }
}

// Paparazzi 2.x classes need a JDK 21 toolchain.
kotlin { jvmToolchain(21) }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

tasks.withType<Test>().configureEach {
  maxParallelForks = (findProperty("forks") as String?)?.toInt() ?: 1
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(libs.junit)
}
'''


def body(i: int) -> str:
    """Five shapes of UI with some weight: text, rows, buttons, lists, cards."""
    kind = i % 5
    if kind == 0:
        lines = "\n".join(f'    Text("Line {j} of card {i}", style = MaterialTheme.typography.bodyLarge)' for j in range(6))
        return f'  Column(Modifier.padding(16.dp)) {{\n    Text("Card {i}", style = MaterialTheme.typography.headlineSmall)\n{lines}\n  }}'
    if kind == 1:
        return (
            f'  Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {{\n'
            f'    repeat(3) {{ n ->\n'
            f'      Box(Modifier.size(72.dp).background(Color(0xFF3366FF.toInt() + n * 0x002200 + {i})), contentAlignment = Alignment.Center) {{\n'
            f'        Text("{i}.$n", color = Color.White)\n'
            f'      }}\n'
            f'    }}\n'
            f'  }}'
        )
    if kind == 2:
        return (
            f'  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {{\n'
            f'    Button(onClick = {{}}) {{ Text("Primary {i}") }}\n'
            f'    OutlinedButton(onClick = {{}}) {{ Text("Secondary {i}") }}\n'
            f'    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium) {{ Text("Surface {i}", Modifier.padding(12.dp)) }}\n'
            f'  }}'
        )
    if kind == 3:
        return (
            f'  Column(Modifier.padding(8.dp)) {{\n'
            f'    repeat(12) {{ n ->\n'
            f'      Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {{\n'
            f'        Box(Modifier.size(24.dp).background(Color.Gray))\n'
            f'        Text("Row $n of list {i}", Modifier.padding(start = 12.dp))\n'
            f'      }}\n'
            f'      HorizontalDivider()\n'
            f'    }}\n'
            f'  }}'
        )
    return (
        f'  Card(Modifier.padding(16.dp)) {{\n'
        f'    Column(Modifier.padding(16.dp)) {{\n'
        f'      Text("Card title {i}", style = MaterialTheme.typography.titleLarge)\n'
        f'      Text("A longer description for card {i} that wraps over more than one line on a phone screen.")\n'
        f'    }}\n'
        f'  }}'
    )


MAIN_HEADER = '''package bench

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
'''

TEST_HEADER = '''package bench

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
'''


def main(n: int) -> None:
    if BENCH.exists():
        shutil.rmtree(BENCH)
    main_dir = BENCH / "src/main/kotlin/bench"
    test_dir = BENCH / "src/test/kotlin/bench"
    main_dir.mkdir(parents=True)
    test_dir.mkdir(parents=True)
    (BENCH / "build.gradle.kts").write_text(BUILD)

    for file_index, start in enumerate(range(0, n, PER_FILE)):
        ids = range(start, min(start + PER_FILE, n))
        main = [MAIN_HEADER]
        test = [TEST_HEADER, f"class Gen{file_index}Test {{", "  @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)\n"]
        for i in ids:
            night = ", uiMode = Configuration.UI_MODE_NIGHT_YES" if i % 7 == 0 else ""
            main.append(f"@Composable\nfun Card{i}() {{\n{body(i)}\n}}\n")
            main.append(f"@Preview(showBackground = true{night})\n@Composable\nfun Card{i}Preview() {{ Card{i}() }}\n")
            test.append(f"  @Test fun card{i}() = paparazzi.snapshot {{ Card{i}() }}")
        test.append("}")
        (main_dir / f"Gen{file_index}.kt").write_text("\n".join(main))
        (test_dir / f"Gen{file_index}Test.kt").write_text("\n".join(test) + "\n")

    print(f"generated {n} composables / previews / paparazzi tests in {BENCH}")


if __name__ == "__main__":
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        sys.exit("usage: generate.py N")
    main(int(sys.argv[1]))
