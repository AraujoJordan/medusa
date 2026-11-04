#!/usr/bin/env python3
"""Generate isolated, disposable single/multi-module consumers for the six-tool benchmark."""
import argparse
import importlib.util
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('corpus', ROOT / 'benchmark/generate.py')
corpus = importlib.util.module_from_spec(spec)
spec.loader.exec_module(corpus)
TOOLS = ['medusa', 'paparazzi', 'roborazzi', 'google', 'shot', 'dropshots']
VERSIONS = {'medusa':'0.2.0', 'paparazzi':'2.0.0-alpha05.1', 'roborazzi':'1.77.0', 'google':'0.0.1-alpha16', 'shot':'6.1.0', 'dropshots':'0.6.0'}
PLUGINS = {'medusa':'io.github.araujojordan.medusa', 'paparazzi':'app.cash.paparazzi', 'roborazzi':'io.github.takahirom.roborazzi', 'dropshots':'com.dropbox.dropshots'}

def write(root, name, text):
    p = root / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)


def generate(destination, tool, topology, n=50):
    p = destination / tool / topology
    if p.exists():
        raise SystemExit(f'{p} already exists; use another output directory to preserve measurements')
    p.mkdir(parents=True)
    legacy = tool == 'shot'
    agp = '8.13.2' if legacy else ('9.5.0-alpha03' if tool == 'google' else '9.0.1')
    gradle = '8.13' if legacy else ('9.6.0' if tool == 'google' else '9.1.0')
    kotlin = '2.2.21' if legacy else '2.3.20'
    modules = ['app'] if topology == 'single' else ['app', 'lib1', 'lib2', 'lib3', 'lib4']
    for name in ['gradlew', 'gradle/wrapper/gradle-wrapper.jar']:
        dest = p / name; dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, dest)
    write(p, 'gradle/wrapper/gradle-wrapper.properties', f'distributionUrl=https\\://services.gradle.org/distributions/gradle-{gradle}-bin.zip\nnetworkTimeout=120000\n')
    write(p, 'settings.gradle.kts', '''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "library-benchmark"
''' + '\n'.join(f'include(":{m}")' for m in modules))
    write(p, 'gradle.properties', '''org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=false
org.gradle.configuration-cache=false
org.gradle.parallel=false
android.useAndroidX=true
android.experimental.enableScreenshotTest=true
android.experimental.testSuiteSupport=true
''')
    root_plugins = '\n'.join(f'  id("com.android.{kind}") version "{agp}" apply false' for kind in ['application','library'])
    root_plugins += f'\n  id("org.jetbrains.kotlin.plugin.compose") version "{kotlin}" apply false\n'
    if legacy:
        root_plugins += f'  id("org.jetbrains.kotlin.android") version "{kotlin}" apply false\n'
    if tool in PLUGINS:
        root_plugins += f'  id("{PLUGINS[tool]}") version "{VERSIONS[tool]}" apply false\n'
    buildscript = '''buildscript { repositories { google(); mavenCentral() }; dependencies { classpath("com.karumi:shot:6.1.0") } }
''' if legacy else ''
    write(p, 'build.gradle.kts', buildscript + 'plugins {\n'+root_plugins+'}\n')
    for index, module in enumerate(modules):
        m = p/module
        ids = range(index*n//len(modules), (index+1)*n//len(modules))
        plugins = f'  id("com.android.{"application" if module == "app" else "library"}")\n  id("org.jetbrains.kotlin.plugin.compose")\n'
        if legacy: plugins += '  id("org.jetbrains.kotlin.android")\n'
        if tool in PLUGINS: plugins += f'  id("{PLUGINS[tool]}")\n'
        runner = 'com.karumi.shot.ShotTestRunner' if legacy else 'androidx.test.runner.AndroidJUnitRunner'
        android_extra = f'  defaultConfig {{ testApplicationId = "bench.{module}.test" }}\n' if legacy and module != 'app' else ''
        if tool == 'roborazzi': android_extra += '  testOptions { unitTests.isIncludeAndroidResources = true }\n'
        if tool == 'google': android_extra += '''  testOptions { screenshotTests.create("screenshotTest") {
    engineVersion = "0.0.1-alpha16"
    targetVariants.add("debug")
    dependencies {
      implementation("androidx.compose.ui:ui-tooling")
      implementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
    }
  } }
'''
        build = 'plugins {\n'+plugins+'}\n'+('apply(plugin = "shot")\n' if legacy else '')
        build += f'''android {{
  namespace = "bench.{module}"
  compileSdk = 36
  defaultConfig {{ minSdk = 26; testInstrumentationRunner = "{runner}"{'; targetSdk = 35' if module == 'app' else ''} }}
  buildFeatures {{ compose = true; buildConfig = true }}
  packaging {{ resources.excludes.add("META-INF/**") }}
{android_extra}}}
kotlin {{ jvmToolchain(21) }}
tasks.withType<Test>().configureEach {{ maxParallelForks = 1; maxHeapSize = "4g" }}
dependencies {{
  implementation(platform("androidx.compose:compose-bom:2026.03.01"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-tooling")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation("junit:junit:4.13.2")
'''
        if module == 'app':
            build += '\n'.join(f'  implementation(project(":{dep}"))' for dep in modules[1:])+'\n'
        if tool == 'roborazzi':
            build += '''  testImplementation("org.robolectric:robolectric:4.14.1")
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.test.ext:junit:1.3.0")
  testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
  testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
'''
        if tool in ['shot','dropshots']:
            build += '''  androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
'''
        write(m, 'build.gradle.kts', build+'}\n')
        write(m, 'src/main/AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:theme="@android:style/Theme.Material.Light.NoActionBar" /></manifest>')
        header = corpus.MAIN_HEADER.replace('package bench',f'package bench.{module}')
        header += '\nimport androidx.compose.foundation.layout.fillMaxSize\n'
        source = [header, '@Composable\nfun BenchmarkFrame(content: @Composable () -> Unit) { Box(Modifier.size(320.dp, 640.dp).background(Color.White)) { content() } }\n']
        for i in ids:
            source += [f'@Composable\nfun Card{i}() {{\n{corpus.body(i)}\n}}\n']
            if tool == 'medusa':
                source += [f'@Preview(widthDp = 320, heightDp = 640, showBackground = true, device = "spec:width=320dp,height=640dp,dpi=160")\n@Composable\nfun Card{i}Preview() {{ BenchmarkFrame {{ Card{i}() }} }}\n']
        write(m, f'src/main/kotlin/bench/{module}/Cards.kt', '\n'.join(source))
        if tool == 'google':
            tests = f'package bench.{module}\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.tooling.preview.Preview\nimport com.android.tools.screenshot.PreviewTest\n'
            tests += '\n'.join(f'@PreviewTest\n@Preview(widthDp = 320, heightDp = 640, showBackground = true, device = "spec:width=320dp,height=640dp,dpi=160")\n@Composable\nfun card{i}() {{ BenchmarkFrame {{ Card{i}() }} }}' for i in ids)
            write(m, f'src/screenshotTest/kotlin/bench/{module}/Screenshots.kt', tests)
        elif tool != 'medusa':
            header = f'package bench.{module}\nimport org.junit.Rule\nimport org.junit.Test\n'
            annotation = ''
            if tool == 'paparazzi':
                header += 'import app.cash.paparazzi.Paparazzi\nimport app.cash.paparazzi.DeviceConfig\n'
                rule = '@get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 640, xdpi = 160, ydpi = 160, density = com.android.resources.Density.MEDIUM, softButtons = false), showSystemUi = false)'
                capture = lambda i: f'paparazzi.snapshot {{ BenchmarkFrame {{ Card{i}() }} }}'
            else:
                header += '''import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
'''
                annotation = '@RunWith(AndroidJUnit4::class)\n'
                rule = '@get:Rule val compose = createComposeRule()'
                if tool == 'roborazzi':
                    header += '''import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
'''
                    annotation += '@Config(sdk = [35], qualifiers = "w320dp-h720dp-mdpi")\n@GraphicsMode(GraphicsMode.Mode.NATIVE)\n'
                    capture = lambda i: f'{{ compose.setContent {{ BenchmarkFrame {{ Card{i}() }} }}; compose.onRoot().captureRoboImage() }}'
                elif tool == 'shot':
                    header += 'import com.karumi.shot.ScreenshotTest\n'
                    capture = lambda i: f'{{ compose.setContent {{ BenchmarkFrame {{ Card{i}() }} }}; compareScreenshot(compose) }}'
                else:
                    header += '''import com.dropbox.dropshots.Dropshots
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
'''
                    rule += '''\n  private val screenshots = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "screenshots/${androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.packageName}")
  @get:Rule val dropshots = Dropshots(rootScreenshotDirectory = screenshots, recordScreenshots = java.io.File(screenshots, ".isRecordingScreenshots").exists())'''
                    capture = lambda i: f'{{ compose.setContent {{ BenchmarkFrame {{ Card{i}() }} }}; dropshots.assertSnapshot(compose.onRoot().captureToImage().asAndroidBitmap()) }}'
            # Ten cases/class for all JUnit tools. One process/fork; no batching 50 cases into one test.
            for start in range(ids.start,ids.stop,10):
                tests = header + f'\n{annotation}class Screenshots{start}Test'+(' : ScreenshotTest' if tool == 'shot' else '')+' {\n  '+rule+'\n'
                tests += '\n'.join(f'  @Test fun card{i}() '+('= ' if tool == 'paparazzi' else '')+capture(i) for i in range(start,min(start+10,ids.stop)))
                source_set = 'androidTest' if tool in ['shot','dropshots'] else 'test'
                write(m,f'src/{source_set}/kotlin/bench/{module}/Screenshots{start}Test.kt',tests+'\n}\n')
    return p

if __name__ == '__main__':
    ap=argparse.ArgumentParser();ap.add_argument('destination',type=Path);ap.add_argument('--tools',default=','.join(TOOLS));ap.add_argument('--topologies',default='single,multi');ap.add_argument('--n',type=int,default=50)
    args=ap.parse_args()
    for t in args.tools.split(','):
        for topology in args.topologies.split(','): print(generate(args.destination,t,topology,args.n))
