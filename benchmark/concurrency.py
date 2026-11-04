#!/usr/bin/env python3
"""How many modules should verify at once? Sweeps medusa.maxConcurrentRenders on fixtures/scale.

  concurrency.py run [--caps 1,2,3,4,6,8,10,16] [--warm 2]    # writes benchmark/results/concurrency.json
  concurrency.py report                                        # writes benchmark/CONCURRENCY.md

For every cap: stop all Gradle daemons, one cold run, then warm runs. While a run is going a sampler records, twice a second,
the summed resident memory of every Gradle related process (outer daemon, nested build daemons, Gradle clients, the CLI's
gradle-build-system helpers, Kotlin daemons) and the system wide CPU use from `top`.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURE = ROOT / "fixtures/scale"
OUT = Path(__file__).resolve().parent / "results" / "concurrency.json"
GRADLE_ARGS = []  # extra arguments for every ./gradlew call (set by --gradle-arg)
PATTERNS = ("GradleDaemon", "GradleWrapperMain", "gradle-build-system", "KotlinCompileDaemon", "kotlin.daemon", "org.gradle.launcher")
CORES = os.cpu_count() or 1


def read(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def cgroup_limits():
    """CPU and memory limits of the container this runs in (cgroup v2, then v1), or None outside a container."""
    cpu_max, mem_max = read("/sys/fs/cgroup/cpu.max"), read("/sys/fs/cgroup/memory.max")
    cpus = mem = None
    if cpu_max:
        quota, period = cpu_max.split()
        cpus = None if quota == "max" else int(quota) / int(period)
        mem = None if mem_max in (None, "max") else int(mem_max)
    else:
        quota, period = read("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"), read("/sys/fs/cgroup/cpu/cpu.cfs_period_us")
        if quota and int(quota) > 0:
            cpus = int(quota) / int(period)
        v1 = read("/sys/fs/cgroup/memory/memory.limit_in_bytes")
        mem = int(v1) if v1 and int(v1) < 2**60 else None
    return {"cpus": cpus, "memory_bytes": mem}


def cgroup_cpu_usec():
    stat = read("/sys/fs/cgroup/cpu.stat")
    if stat:
        for line in stat.splitlines():
            if line.startswith("usage_usec"):
                return int(line.split()[1])
    v1 = read("/sys/fs/cgroup/cpuacct/cpuacct.usage")
    return int(v1) // 1000 if v1 else None


def cgroup_oom_kills():
    events = read("/sys/fs/cgroup/memory.events")
    if events:
        for line in events.splitlines():
            if line.startswith("oom_kill "):
                return int(line.split()[1])
    return 0


class Sampler(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.stop = threading.Event()
        self.rss_mb, self.procs, self.cpu_busy = [], [], []
        self.top = None

    def run(self):
        in_cgroup = cgroup_cpu_usec() is not None
        if in_cgroup:
            # Busy percentage of the container's own CPU allowance, not of the whole VM.
            limits = cgroup_limits()
            allowance = limits["cpus"] or os.cpu_count() or 1
            threading.Thread(target=self._read_cgroup_cpu, args=(allowance,), daemon=True).start()
        elif platform_is_mac():
            self.top = subprocess.Popen(["top", "-l", "0", "-s", "1", "-n", "0"], stdout=subprocess.PIPE, text=True)
            threading.Thread(target=self._read_top, daemon=True).start()
        while not self.stop.is_set():
            out = subprocess.run(["ps", "-axo", "rss=,command="], capture_output=True, text=True).stdout
            total, count = 0, 0
            for line in out.splitlines():
                rss, _, cmd = line.strip().partition(" ")
                if any(p in cmd for p in PATTERNS) and "ps -axo" not in cmd:
                    total += int(rss)
                    count += 1
            self.rss_mb.append(total / 1024)
            self.procs.append(count)
            self.stop.wait(0.5)
        if self.top:
            self.top.terminate()

    def _read_cgroup_cpu(self, allowance):
        last, last_t = cgroup_cpu_usec(), time.perf_counter()
        while not self.stop.wait(1.0):
            now, now_t = cgroup_cpu_usec(), time.perf_counter()
            self.cpu_busy.append(min(100.0, 100.0 * (now - last) / 1e6 / (now_t - last_t) / allowance))
            last, last_t = now, now_t

    def _read_top(self):
        for line in self.top.stdout:
            m = re.search(r"CPU usage: ([\d.]+)% user, ([\d.]+)% sys, ([\d.]+)% idle", line)
            if m:
                self.cpu_busy.append(100.0 - float(m.group(3)))


def platform_is_mac():
    return os.uname().sysname == "Darwin"


def gradle(*args):
    return subprocess.run(["./gradlew", "--console=plain", "-q", *GRADLE_ARGS, *args], cwd=FIXTURE, capture_output=True, text=True)


def measure(cap: int):
    sampler = Sampler()
    sampler.start()
    time.sleep(1.5)  # a baseline before the run starts
    baseline = sampler.rss_mb[-1] if sampler.rss_mb else 0
    oom_before = cgroup_oom_kills()
    start = time.perf_counter()
    proc = gradle("medusaVerify", "--parallel", f"-Pmedusa.maxConcurrentRenders={cap}", "-Pmedusa.differ=yiq")
    elapsed = time.perf_counter() - start
    n_busy = max(1, int(elapsed))
    busy = sampler.cpu_busy[-n_busy:] if sampler.cpu_busy else [0]
    sampler.stop.set()
    sampler.join(timeout=5)
    return {
        "seconds": round(elapsed, 1),
        "ok": proc.returncode == 0,
        "oom_kills": cgroup_oom_kills() - oom_before,
        "failure": None if proc.returncode == 0 else (proc.stdout + proc.stderr)[-400:],
        "peak_rss_mb": round(max(sampler.rss_mb)),
        "baseline_rss_mb": round(baseline),
        "peak_procs": max(sampler.procs),
        "cpu_busy_mean": round(statistics.mean(busy), 1),
        "cpu_busy_max": round(max(busy), 1),
    }


def run(args):
    global FIXTURE, OUT
    FIXTURE, OUT = Path(args.fixture), Path(args.out)
    GRADLE_ARGS.extend(args.gradle_arg)
    caps = [int(c) for c in args.caps.split(",")]
    data = json.loads(OUT.read_text()) if OUT.exists() else {}
    limits = cgroup_limits()
    mem_bytes = limits["memory_bytes"] or (int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout)
                                          if platform_is_mac() else int(read("/proc/meminfo").split()[1]) * 1024)
    data["_env"] = {"cores": limits["cpus"] or CORES, "mem_gb": round(mem_bytes / 2**30, 1), "container": limits["cpus"] is not None or limits["memory_bytes"] is not None,
                    "os": os.uname().sysname + " " + os.uname().machine,
                    "modules": len([p for p in FIXTURE.iterdir() if p.name.startswith("m") and p.is_dir()]), "date": time.strftime("%Y-%m-%d %H:%M")}
    for cap in caps:
        gradle("--stop")
        time.sleep(3)
        runs = {"cold": [measure(cap)], "warm": []}
        print(f"cap {cap:2} cold  {runs['cold'][0]}", flush=True)
        if not runs["cold"][0]["ok"]:
            print("  cold run failed, skipping the warm runs of this cap", flush=True)
            data[str(cap)] = runs
            OUT.parent.mkdir(exist_ok=True)
            OUT.write_text(json.dumps(data, indent=2))
            continue
        for _ in range(args.warm):
            runs["warm"].append(measure(cap))
            print(f"cap {cap:2} warm  {runs['warm'][-1]}", flush=True)
        data[str(cap)] = runs
        OUT.parent.mkdir(exist_ok=True)
        OUT.write_text(json.dumps(data, indent=2))


def report(out=None):
    data = json.loads(Path(out or OUT).read_text())
    env = data.pop("_env")
    lines = [f"# maxConcurrentRenders sweep", "", f"{env['cores']} cores, {env['mem_gb']} GB, {env['modules']} modules (3 previews each), {env['date']}. `-Pmedusa.differ=yiq`.", "",
             "| cap | cold s | warm s (median) | warm min-max | peak memory warm MB | peak processes | CPU busy % (mean/max, warm) |", "|---|---|---|---|---|---|---|"]
    for cap in sorted(data, key=int):
        cold, warm = data[cap]["cold"][0], data[cap]["warm"]
        ws = [w["seconds"] for w in warm]
        lines.append(f"| {cap} | {cold['seconds']} | {statistics.median(ws):.1f} | {min(ws)}-{max(ws)} | {max(w['peak_rss_mb'] for w in warm)} | "
                     f"{max(w['peak_procs'] for w in warm)} | {statistics.mean(w['cpu_busy_mean'] for w in warm):.0f} / {max(w['cpu_busy_max'] for w in warm):.0f} |")
    Path(__file__).resolve().parent.joinpath("CONCURRENCY.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--caps", default="1,2,3,4,6,8,10,16")
    r.add_argument("--warm", type=int, default=2)
    r.add_argument("--fixture", default=str(FIXTURE))
    r.add_argument("--out", default=str(OUT))
    r.add_argument("--gradle-arg", action="append", default=[], help="extra argument for every ./gradlew call, repeatable")
    sub.add_parser("report").add_argument("--out", default=str(OUT))
    a = p.parse_args()
    run(a) if a.cmd == "run" else report(a.out)
