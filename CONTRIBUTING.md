# Contributing

Thanks for helping! Bug reports, corrections to the [comparison](docs/comparison.md) and pull requests are all welcome.

## Layout

- `tools/`: the Gradle plugin (`medusa-gradle-plugin`) and the library (`medusa-core`). This is the code that gets published.
- `sample/`: a small Compose app whose previews have recorded goldens. `./gradlew medusaVerify` there is the end-to-end check.
- `fixtures/multimodule`: a multi-module build used to check nested modules, aggregator mode and custom annotations.
- `benchmark/`: scripts and write-ups of every measurement quoted in the docs.
- `docs/`: user documentation. `docs/media/make-media.sh` regenerates the README images.

## Running the tests

```bash
cd tools && ./gradlew test
```

The unit and Gradle TestKit tests use a fake `android` script and a fake AGP, so they need no SDK. Tests that use the real odiff or
ImageMagick skip themselves when the tool is missing (CI on macOS installs both).

## End to end

You need the Android CLI (`./gradlew medusaSetup` installs it) and an Android SDK. In `sample/`, run `./gradlew medusaVerify`; it should
pass against the committed goldens.

## Pull requests

- Keep changes focused, and add a test when behaviour changes.
- If you change a number in the docs, change it from a measurement in `benchmark/`, not from memory.
- Releases are described in [RELEASING.md](RELEASING.md).
