#!/usr/bin/env python3
"""Compare verify worker counts on a generated 50-preview fixture.
Run with JDK 21 after `python3 benchmark/generate.py 50`. Records goldens first.
One warm-up per worker count, three rotated measured rounds; run without other builds.
Uses existing Gradle cache settings; Medusa verify is untracked and renders every time.
"""
import os, subprocess, time, json, statistics
from pathlib import Path
os.chdir(Path(__file__).resolve().parent.parent)
if not Path('bench/src/main').is_dir():
 raise SystemExit('First run python3 benchmark/generate.py 50. Use JDK 21.')
def run(task, workers):
 start=time.perf_counter()
 p=subprocess.run(['./gradlew',task,'--console=plain','--info',f'-Pmedusa.compareParallelism={workers}'],capture_output=True,text=True)
 if p.returncode: raise RuntimeError(p.stdout[-4000:]+p.stderr[-1000:])
 return {'seconds':time.perf_counter()-start,'timings':[l for l in p.stdout.splitlines() if 'Medusa pipeline:' in l or 'Medusa discovery:' in l]}
run(':bench:medusaRecord',1)
for w in [1,2,10]:run(':bench:medusaVerify',w)
results={str(w):[] for w in [1,2,10]}
for order in [[1,2,10],[10,1,2],[2,10,1]]:
 for w in order:
  r=run(':bench:medusaVerify',w); results[str(w)].append(r); print(w,r,flush=True)
Path('benchmark/QUEUE_RESULTS.json').write_text(json.dumps({'previews':50,'runs':3,'renderer':'daemon, one render batch','differ':'odiff','build_cache':True,'results':results},indent=2)+'\n')
