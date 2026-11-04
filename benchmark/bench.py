#!/usr/bin/env python3
"""Benchmark Medusa against Paparazzi on the generated `bench` module.

  bench.py run --n 50 [--runs 3] [--configs a,b] [--scenarios verify,cold,record]
  bench.py report            # rebuilds RESULTS.md from benchmark/results/*.json

Every measurement is the wall-clock time of one `./gradlew` invocation (Gradle daemon already warm, one untimed
warm-up run first). Scenarios:

  verify  nothing changed; force the rendering to run again (Paparazzi: cleanTestDebugUnitTest first)
  cold    `clean` the module first, so compile + render from scratch
  record  one composable edited (incremental compile), then record
"""
import argparse
import json
import os
import platform
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = Path(__file__).resolve().parent / "results"
CORES = os.cpu_count() or 4
GEN0 = ROOT / "bench/src/main/kotlin/bench/Gen0.kt"

# name -> (tool, extra gradle args). Order matters: configurations that start the CLI daemon come last.
CONFIGS = {
    "medusa": ("medusa", []),
    "medusa-no-daemon": ("medusa", ["-Pmedusa.useDaemon=false"]),
    "paparazzi-1": ("paparazzi", ["-Pforks=1"]),
    f"paparazzi-{CORES}": ("paparazzi", [f"-Pforks={CORES}"]),
    "medusa-daemon": ("medusa", ["-Pmedusa.useDaemon=true"]),
    "medusa-daemon-render2": ("medusa", ["-Pmedusa.useDaemon=true", "-Pmedusa.renderParallelism=2"]),
}
TASKS = {
    "medusa": {"verify": ":bench:medusaVerify", "record": ":bench:medusaRecord"},
    "paparazzi": {"verify": ":bench:verifyPaparazziDebug", "record": ":bench:recordPaparazziDebug"},
}


def gradle(*args: str, log: Path | None = None) -> tuple[float, int]:
    cmd = ["./gradlew", "--console=plain", "-q", *args]
    start = time.perf_counter()
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    elapsed = time.perf_counter() - start
    if log is not None and proc.returncode != 0:
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(f"$ {' '.join(cmd)}\n{proc.stdout}\n{proc.stderr}")
    return elapsed, proc.returncode


def edit_source(run: int) -> None:
    """Change the text of one composable so the next compile and render really differ."""
    text = GEN0.read_text()
    text = re.sub(r'Text\("Card 0[^"]*"', f'Text("Card 0 r{run}"', text, count=1)
    GEN0.write_text(text)


def measure(name: str, tool: str, extra: list[str], scenario: str, runs: int, n: int) -> dict:
    task = TASKS[tool]["record" if scenario == "record" else "verify"]
    prepare = {"paparazzi": ":bench:cleanTestDebugUnitTest"}.get(tool)
    times, failures = [], 0
    log = RESULTS / f"fail-N{n}-{name}-{scenario}.log"
    warmups = 0 if scenario == "cold" else 1
    for i in range(-warmups, runs):
        if scenario == "cold":
            gradle(":bench:clean")
        elif scenario == "verify" and prepare:
            gradle(prepare)
        elif scenario == "record":
            edit_source(f"{name}-{i}")
        elapsed, rc = gradle(task, *extra, log=log)
        label = "warm-up" if i < 0 else f"run {i + 1}"
        suspicious = "  <-- suspiciously fast, cached?" if rc == 0 and n >= 10 and elapsed < 1.5 else ""
        print(f"    {name:24} {scenario:7} {label:8} {elapsed:6.1f}s rc={rc}{suspicious}", flush=True)
        if i < 0:
            continue
        if rc != 0:
            failures += 1
        else:
            times.append(elapsed)
    return {"times": times, "failures": failures}


def setup(n: int) -> None:
    print(f"== N={n}: generate, record both tools, check both verify", flush=True)
    subprocess.run([sys.executable, str(ROOT / "benchmark/generate.py"), str(n)], check=True)
    for tool in ("medusa", "paparazzi"):
        elapsed, rc = gradle(TASKS[tool]["record"], log=RESULTS / f"fail-setup-{tool}.log")
        if rc != 0:
            sys.exit(f"setup: {tool} record failed, see {RESULTS}")
        print(f"   {tool} record {elapsed:.1f}s", flush=True)
    for tool in ("medusa", "paparazzi"):
        if tool == "paparazzi":
            gradle(":bench:cleanTestDebugUnitTest")
        elapsed, rc = gradle(TASKS[tool]["verify"], log=RESULTS / f"fail-setup-{tool}.log")
        if rc != 0:
            sys.exit(f"setup: {tool} verify failed right after record, see {RESULTS}")


def versions() -> dict:
    def out(*cmd):
        try:
            return subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT).stdout.strip().splitlines()[0]
        except Exception:
            return "?"

    return {
        "machine": f"{out('sysctl', '-n', 'machdep.cpu.brand_string')}, {CORES} cores, {platform.platform()}",
        "memory_gb": round(int(out("sysctl", "-n", "hw.memsize") or 0) / 2**30),
        "android_cli": out(str(Path.home() / ".local/bin/android"), "-V").splitlines()[-1] if True else "?",
        "gradle": out("./gradlew", "--version", "-q") or "?",
        "java": out("java", "-version"),
    }


PROPS = ROOT / "gradle.properties"


def run(args) -> None:
    """Runs with the Gradle build cache off (restored afterwards, also on failure).

    The project's gradle.properties enables `org.gradle.caching`; with it, Paparazzi's test task and the compile
    steps are restored from the cache and nothing is rendered, which would make both tools look absurdly fast.
    It must be the file, not a command-line flag, because the Android CLI starts its own nested Gradle build
    that only sees the file.
    """
    original = PROPS.read_text()
    PROPS.write_text(re.sub(r"(?m)^org\.gradle\.caching=.*$", "org.gradle.caching=false", original))
    try:
        run_benchmarks(args)
    finally:
        PROPS.write_text(original)
        print("restored gradle.properties", flush=True)


def run_benchmarks(args) -> None:
    RESULTS.mkdir(exist_ok=True)
    configs = args.configs.split(",") if args.configs else list(CONFIGS)
    scenarios = args.scenarios.split(",")
    setup(args.n)
    result = {"n": args.n, "runs": args.runs, "env": versions(), "date": time.strftime("%Y-%m-%d %H:%M"), "data": {}}
    # `record` edits the sources, so it must come last for the goldens to stay valid for the other scenarios.
    for scenario in sorted(scenarios, key=lambda s: s == "record"):
        print(f"== scenario {scenario}", flush=True)
        for name in configs:
            tool, extra = CONFIGS[name]
            result["data"].setdefault(name, {})[scenario] = measure(name, tool, extra, scenario, args.runs, args.n)
    (RESULTS / f"N{args.n}.json").write_text(json.dumps(result, indent=2))
    print(f"wrote {RESULTS / f'N{args.n}.json'}")
    report()


def fmt(values: list[float]) -> str:
    return f"{statistics.median(values):.1f}  ({min(values):.1f}-{max(values):.1f})" if values else "failed"


def report() -> None:
    files = sorted(RESULTS.glob("N*.json"), key=lambda p: int(re.sub(r"\D", "", p.stem)))
    if not files:
        return
    lines = ["# Medusa vs Paparazzi benchmark", ""]
    first = json.loads(files[0].read_text())
    lines += [f"Machine: {first['env']['machine']}, {first['env']['memory_gb']} GB", f"Android CLI {first['env']['android_cli']}", ""]
    lines += [
        "Wall-clock seconds of one `./gradlew` call, median (min-max) over the measured runs, Gradle daemon warm.",
        "Both tools render the same generated composables (`benchmark/generate.py`).",
        "",
    ]
    lines += [
        "**Reading these numbers.** One machine, run counts shown per table, synthetic composables, Gradle build cache off.",
        "Each call includes about 1-2 s of Gradle start-up for both tools. The tools do not render identical pixels:",
        "Paparazzi renders with layoutlib in a `Pixel 5` frame, the Android CLI with its own renderer on `medium_phone`,",
        "so this compares time to a verdict, not image fidelity. Paparazzi is 2.0.0-alpha05.1, Medusa is the local working tree.",
        "",
    ]
    names = {"verify": "verify, nothing changed (rendering forced to rerun)", "cold": "cold: `clean`, then verify", "record": "record after editing one composable"}
    for f in files:
        d = json.loads(f.read_text())
        lines += [f"## {d['n']} previews ({d['date']}, {d['runs']} measured runs)", ""]
        for scenario in ("verify", "cold", "record"):
            if not any(scenario in v for v in d["data"].values()):
                continue
            lines += [f"**{names[scenario]}**", "", "| configuration | seconds | previews/s (median) |", "|---|---|---|"]
            for config, per in d["data"].items():
                if scenario in per:
                    t = per[scenario]["times"]
                    rate = f"{d['n'] / statistics.median(t):.1f}" if t else "-"
                    lines.append(f"| {config} | {fmt(t)} | {rate} |")
            lines.append("")
    (Path(__file__).resolve().parent / "RESULTS.md").write_text("\n".join(lines) + "\n")
    print("wrote benchmark/RESULTS.md")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--n", type=int, required=True)
    r.add_argument("--runs", type=int, default=5)
    r.add_argument("--configs", default="")
    r.add_argument("--scenarios", default="verify,cold,record")
    sub.add_parser("report")
    a = p.parse_args()
    run(a) if a.cmd == "run" else report()
